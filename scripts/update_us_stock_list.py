import requests
import json

API_KEY = ""

def build_data():
    url = f"https://finnhub.io/api/v1/stock/symbol?exchange=US&token={API_KEY}"
    res = requests.get(url)

    data = res.json()

    result = []

    for s in data:
        symbol = s.get("symbol")

        result.append({
            "name": s.get("description"),
            "code": symbol,
            "market": "US",
            "industry": "",
            "stockType": "ETF" if s.get("type") in ("ETF", "ETP") else "Stock"
        })

    return result

def build_data_nasdaq():
    import requests
    import csv
    import io

    urls = [
        {
            "url": "https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt",
            "symbol_field": "Symbol"
        },
        {
            "url": "https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt",
            "symbol_field": "ACT Symbol"
        }
    ]

    result = []

    for source in urls:
        res = requests.get(source["url"], timeout=30)
        res.raise_for_status()

        reader = csv.DictReader(
            io.StringIO(res.text),
            delimiter="|"
        )

        for s in reader:
            symbol = s.get(source["symbol_field"])

            if not symbol:
                continue

            # Nasdaq 檔案尾端的產生時間不是股票資料
            if symbol.startswith("File Creation Time:"):
                continue

            # 排除測試商品
            if s.get("Test Issue") == "Y":
                continue

            result.append({
                "name": s.get("Security Name", "").strip(),
                "code": symbol.strip(),
                "market": "US",
                "industry": "",
                "stockType": "ETF" if s.get("ETF") == "Y" else "Stock"
            })

    return result
    
if __name__ == "__main__":
    data = build_data()

    with open("us_stocks.json", "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    print(f"完成，共 {len(data)} 筆")
    
    
    
    data = build_data_nasdaq()

    with open("us_stocks_nasdaq.json", "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    print(f"完成，共 {len(data)} 筆")
