---
name: shopee_price_search
title: Shopee price search
description: Search Shopee for a product and return the top 5 prices found on screen.
version: 1
triggers: shopee, cari harga, cek harga, bandingkan harga, harga di shopee
---

# Shopee price search

Cari harga sebuah produk di Shopee dan kembalikan 5 harga teratas yang terlihat di layar.

## Kapan dipakai

- Tuan menyebut "shopee" atau minta "cari/cek/bandingkan harga" sebuah barang.
- Selalu butuh satu parameter: `query` — nama produk yang dicari.

## Cara kerja

1. Buka aplikasi Shopee, tunggu kolom "Cari" muncul.
2. Ketuk kolom pencarian, ketik `query`, lalu submit.
3. Tunggu harga ("Rp") muncul, baca layar, lalu ekstrak sampai 5 harga.
4. Hasil diringkas ke Tuan (`summarize: true`).

## Catatan

- Skill ini hanya MEMBACA layar — tidak membeli, tidak menambah keranjang.
- Kalau Shopee belum terbuka atau kolom "Cari" tidak muncul, langkah yang gagal
  dilewati (`onFail: skip`) dan skill tetap melaporkan apa yang didapat.
- File ini adalah versi SKILL.md (v8.1) dari `shopee_price_search.json`.
  Keduanya berisi langkah yang sama; yang `.md` ini kanonik untuk tes v8.1,
  yang `.json` tetap ada sebagai fallback bila file ini rusak.

```json
{
  "description": "Search Shopee for a product and return the top 5 prices found on screen.",
  "id": "shopee_price_search",
  "maxSteps": 12,
  "name": "Shopee price search",
  "params": [
    {
      "name": "query",
      "required": true
    }
  ],
  "steps": [
    {
      "args": {
        "package": "com.shopee.id"
      },
      "tool": "launchApp"
    },
    {
      "args": {
        "package": "com.shopee.id",
        "text": "Cari",
        "timeoutMs": "8000"
      },
      "onFail": "skip",
      "tool": "waitForText"
    },
    {
      "args": {
        "exact": "false",
        "package": "com.shopee.id",
        "text": "Cari"
      },
      "tool": "tapText"
    },
    {
      "args": {
        "package": "com.shopee.id",
        "text": "$query"
      },
      "tool": "type"
    },
    {
      "args": {
        "package": "com.shopee.id"
      },
      "tool": "submit"
    },
    {
      "args": {
        "package": "com.shopee.id",
        "text": "Rp",
        "timeoutMs": "10000"
      },
      "onFail": "skip",
      "tool": "waitForText"
    },
    {
      "args": {
        "limit": "100",
        "package": "com.shopee.id"
      },
      "storeAs": "screen",
      "tool": "readScreen"
    },
    {
      "args": {
        "max": "5",
        "package": "com.shopee.id",
        "pattern": "Rp\\s?[0-9][0-9.,]*"
      },
      "storeAs": "prices",
      "tool": "extract",
      "when": "last.success"
    }
  ],
  "summarize": true,
  "timeoutMs": 60000,
  "triggers": [
    "shopee",
    "cari harga",
    "cek harga",
    "bandingkan harga",
    "harga di shopee"
  ]
}
```
