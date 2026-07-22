#!/usr/bin/env python3
import argparse
import json
import ssl
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path
from typing import List
from urllib.request import Request, urlopen


TWSE_URL_TEMPLATE = "https://isin.twse.com.tw/isin/C_public.jsp?strMode={mode}"
DEFAULT_OUTPUT = Path("app/src/main/assets/stocks.json")
FILTERED_CATEGORIES = {
    "上市認購",
    "上櫃認購",
    "臺灣存託憑證",
    "不動產投資信託",
    "受益證券",
}


@dataclass
class ParsedRow:
    cells: List[str]


class TableRowParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self._in_tr = False
        self._in_td = False
        self._current_row: List[str] = []
        self._current_cell: List[str] = []
        self.rows: List[ParsedRow] = []

    def handle_starttag(self, tag, attrs):
        if tag == "tr":
            self._in_tr = True
            self._current_row = []
        elif tag == "td" and self._in_tr:
            self._in_td = True
            self._current_cell = []

    def handle_endtag(self, tag):
        if tag == "td" and self._in_td:
            text = "".join(self._current_cell).strip()
            self._current_row.append(text)
            self._in_td = False
        elif tag == "tr" and self._in_tr:
            if self._current_row:
                self.rows.append(ParsedRow(self._current_row))
            self._in_tr = False
            self._current_row = []

    def handle_data(self, data):
        if self._in_td:
            self._current_cell.append(data)


def fetch_html(url: str) -> str:
    request = Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        },
    )
    context = ssl._create_unverified_context()
    with urlopen(request, timeout=30, context=context) as response:
        body = response.read()
    return body.decode("big5", errors="replace")


def parse_stock_list(html: str) -> List[dict]:
    parser = TableRowParser()
    parser.feed(html)

    stocks: List[dict] = []
    stock_type = "股票"

    for row in parser.rows:
        cols = row.cells

        if len(cols) == 1:
            stock_type = cols[0].strip()
            continue

        if len(cols) != 7:
            continue

        full_text = cols[0].strip()
        if "　" not in full_text:
            continue

        code_and_name = [part.strip() for part in full_text.split("　") if part.strip()]
        if len(code_and_name) < 2:
            continue

        if any(bad in stock_type for bad in FILTERED_CATEGORIES):
            continue

        code = code_and_name[0]
        name = code_and_name[1]
        market = cols[3].strip()
        industry = cols[4].strip()

        stocks.append(
            {
                "name": name,
                "code": code,
                "market": market,
                "industry": industry,
                "stockType": stock_type,
            }
        )

    return stocks


def fetch_twse_stocks() -> List[dict]:
    stocks: List[dict] = []
    for mode in ("2", "4", "5"):
        url = TWSE_URL_TEMPLATE.format(mode=mode)
        html = fetch_html(url)
        stocks.extend(parse_stock_list(html))
    return stocks


def write_json(path: Path, stocks: List[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(stocks, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Fetch TWSE stock list and write JSON in the app assets format."
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help=f"Output path (default: {DEFAULT_OUTPUT.as_posix()})",
    )
    args = parser.parse_args()

    stocks = fetch_twse_stocks()
    write_json(args.output, stocks)
    print(f"Wrote {len(stocks)} stocks to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
