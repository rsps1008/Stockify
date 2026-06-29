#!/usr/bin/env python3
"""Yahoo crawler helpers that mirror Stockify's current Kotlin fetchers.

Usage examples:

  python scripts/yahoo_crawler.py quote 2330
  python scripts/yahoo_crawler.py us AAPL
  python scripts/yahoo_crawler.py dividend 2330 --kind cash
  python scripts/yahoo_crawler.py dividend 2330 --kind stock

The script prints JSON to stdout so it can be used for quick checks or piped
into other tooling.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from html.parser import HTMLParser
from typing import Dict, List, Optional, Tuple

import requests


YAHOO_TW_QUOTE_URL = "https://tw.stock.yahoo.com/quote/{stock_code}"
YAHOO_TW_DIVIDEND_URL = "https://tw.stock.yahoo.com/quote/{stock_code}/dividend"
YAHOO_US_CHART_URL = (
    "https://query1.finance.yahoo.com/v8/finance/chart/{stock_code}"
    "?interval=1d&range=1d&includePrePost=false&events=div%2Csplits"
)

USER_AGENT = "Mozilla/5.0"


def _request_text(url: str) -> str:
    response = requests.get(url, headers={"User-Agent": USER_AGENT}, timeout=20)
    response.raise_for_status()
    return response.text


def _request_json(url: str) -> dict:
    response = requests.get(url, headers={"User-Agent": USER_AGENT}, timeout=20)
    response.raise_for_status()
    return response.json()


def _normalize_number(text: Optional[str]) -> Optional[float]:
    if text is None:
        return None
    cleaned = text.strip().replace(",", "").replace("%", "")
    if not cleaned or cleaned == "-":
        return None
    try:
        return float(cleaned)
    except ValueError:
        return None


def _first_match(items: Dict[str, str], keys: Tuple[str, ...]) -> Optional[str]:
    for key in keys:
        for label, value in items.items():
            if key in label:
                return value
    return None


def fetch_taiwan_quote(stock_code: str) -> dict:
    url = YAHOO_TW_QUOTE_URL.format(stock_code=stock_code)
    html = _request_text(url)
    soup = _MiniSoup()
    soup.feed(html)
    items = soup.extract_realtime_items()

    price_text = _first_match(items, ("成交",))
    yesterday_text = _first_match(items, ("昨收",))

    price = _normalize_number(price_text)
    yesterday = _normalize_number(yesterday_text)
    if price is None or yesterday is None or yesterday == 0:
        raise RuntimeError(
            f"Failed to parse Yahoo TW quote for {stock_code}: "
            f"price={price_text!r}, yesterday={yesterday_text!r}"
        )

    change = price - yesterday
    change_percent = (change / yesterday) * 100
    limit_state = "LIMIT_UP" if change_percent >= 9.9 else "LIMIT_DOWN" if change_percent <= -9.9 else "NONE"

    return {
        "market": "TW",
        "stockCode": stock_code,
        "url": url,
        "currentPrice": price,
        "yesterdayPrice": yesterday,
        "change": change,
        "changePercent": change_percent,
        "limitState": limit_state,
        "raw": items,
    }


def fetch_us_quote(stock_code: str) -> dict:
    url = YAHOO_US_CHART_URL.format(stock_code=stock_code)
    root = _request_json(url)
    chart = root.get("chart") or {}
    results = chart.get("result") or []
    result = results[0] if results else {}
    meta = result.get("meta") or {}

    price = meta.get("regularMarketPrice")
    previous_close = meta.get("chartPreviousClose", meta.get("previousClose"))
    change = meta.get("regularMarketChange")
    change_percent = meta.get("regularMarketChangePercent")

    if price is None or previous_close is None:
        raise RuntimeError(f"Missing Yahoo US quote data for {stock_code}: {meta!r}")

    if change is None:
        change = price - previous_close
    if change_percent is None:
        change_percent = ((price - previous_close) / previous_close) * 100 if previous_close else 0.0

    return {
        "market": "US",
        "stockCode": stock_code,
        "url": url,
        "currentPrice": float(price),
        "yesterdayPrice": float(previous_close),
        "change": float(change),
        "changePercent": float(change_percent),
        "limitState": "NONE",
        "raw": meta,
    }


def fetch_latest_dividend(stock_code: str, kind: str = "cash") -> Optional[dict]:
    url = YAHOO_TW_DIVIDEND_URL.format(stock_code=stock_code)
    html = _request_text(url)
    try:
        from bs4 import BeautifulSoup
    except ImportError as exc:  # pragma: no cover - runtime environment dependent
        raise RuntimeError(
            "beautifulsoup4 is required for dividend parsing. "
            "Install it with: pip install beautifulsoup4"
        ) from exc

    soup = BeautifulSoup(html, "html.parser")
    rows = soup.select(".table-body ul > li")

    value_index = 4 if kind == "cash" else 5

    for row in rows:
        cols = [div.get_text(" ", strip=True) for div in row.select("div")]
        if len(cols) < 9:
            continue

        belong = cols[3].strip()
        if not belong:
            continue

        amount = _normalize_number(cols[value_index])
        date_text = cols[8].strip()
        if amount is not None:
            return {
                "market": "TW",
                "stockCode": stock_code,
                "kind": kind,
                "url": url,
                "amount": amount,
                "date": date_text,
                "raw": cols,
            }

    return None


@dataclass
class _TagFrame:
    tag: str
    attrs: Dict[str, str]


class _MiniSoup(HTMLParser):
    """Small purpose-built parser for the current Yahoo page structures."""

    def __init__(self) -> None:
        super().__init__()
        self._stack: List[_TagFrame] = []
        self._in_realtime_section = False
        self._in_realtime_li = False
        self._current_span_text: List[str] = []
        self._current_li_spans: List[str] = []
        self._in_dividend_table = False
        self._in_dividend_li = False
        self._in_dividend_cell = False
        self._current_dividend_text: List[str] = []
        self._current_dividend_row: List[str] = []
        self._dividend_rows: List[List[str]] = []
        self._realtime_items: Dict[str, str] = {}

    def handle_starttag(self, tag, attrs):
        attr_map = {name: value or "" for name, value in attrs}
        self._stack.append(_TagFrame(tag=tag, attrs=attr_map))

        if tag == "section" and attr_map.get("id") == "qsp-overview-realtime-info":
            self._in_realtime_section = True
        elif self._in_realtime_section and tag == "li":
            self._in_realtime_li = True
            self._current_li_spans = []
        elif self._in_realtime_li and tag == "span":
            self._current_span_text = []
        elif tag == "div" and "table-body" in attr_map.get("class", ""):
            self._in_dividend_table = True
        elif self._in_dividend_table and tag == "li":
            self._in_dividend_li = True
            self._current_dividend_row = []
        elif self._in_dividend_li and tag == "div":
            self._in_dividend_cell = True
            self._current_dividend_text = []

    def handle_endtag(self, tag):
        while self._stack:
            frame = self._stack.pop()
            if frame.tag == tag:
                break

        if tag == "span" and self._in_realtime_li:
            text = "".join(self._current_span_text).strip()
            self._current_li_spans.append(text)
            self._current_span_text = []
        elif tag == "li" and self._in_realtime_li:
            if len(self._current_li_spans) == 2:
                key, value = self._current_li_spans
                self._realtime_items[key] = value
            self._in_realtime_li = False
            self._current_li_spans = []
        elif tag == "section" and self._in_realtime_section:
            self._in_realtime_section = False
        elif tag == "div" and self._in_dividend_cell:
            text = "".join(self._current_dividend_text).strip()
            self._current_dividend_row.append(text)
            self._in_dividend_cell = False
            self._current_dividend_text = []
        elif tag == "li" and self._in_dividend_li:
            if self._current_dividend_row:
                self._dividend_rows.append(self._current_dividend_row)
            self._in_dividend_li = False
            self._current_dividend_row = []
        elif tag == "div" and self._in_dividend_table and not self._stack:
            self._in_dividend_table = False

    def handle_data(self, data):
        if self._in_realtime_li and self._stack and self._stack[-1].tag == "span":
            self._current_span_text.append(data)
        elif self._in_dividend_cell and self._stack and self._stack[-1].tag == "div":
            self._current_dividend_text.append(data)

    def extract_realtime_items(self) -> Dict[str, str]:
        return dict(self._realtime_items)

    def extract_dividend_rows(self) -> List[List[str]]:
        return list(self._dividend_rows)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Fetch Yahoo quote/dividend data used by Stockify.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    quote_parser = subparsers.add_parser("quote", help="Fetch TW Yahoo quote page data.")
    quote_parser.add_argument("stock_code", help="TW stock code, e.g. 2330")

    us_parser = subparsers.add_parser("us", help="Fetch US Yahoo chart API data.")
    us_parser.add_argument("stock_code", help="US ticker, e.g. AAPL")

    dividend_parser = subparsers.add_parser("dividend", help="Fetch TW Yahoo dividend page data.")
    dividend_parser.add_argument("stock_code", help="TW stock code, e.g. 2330")
    dividend_parser.add_argument(
        "--kind",
        choices=("cash", "stock"),
        default="cash",
        help="Which dividend type to return (default: cash).",
    )

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()

    if args.command == "quote":
        result = fetch_taiwan_quote(args.stock_code)
    elif args.command == "us":
        result = fetch_us_quote(args.stock_code)
    else:
        result = fetch_latest_dividend(args.stock_code, kind=args.kind)

    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
