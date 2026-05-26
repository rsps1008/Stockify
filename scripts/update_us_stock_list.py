import requests
import json

API_KEY = "d7f3plhr01qpjqqjbjogd7f3plhr01qpjqqjbjp0"

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
            "stockType": "ETF" if s.get("type") == "ETF" else "Stock"
        })

    return result


if __name__ == "__main__":
    data = build_data()

    with open("us_stocks.json", "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    print(f"完成，共 {len(data)} 筆")