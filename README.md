# 自用輸入法（微信風格拼音 + 掃描 Barcode + 語音）

- 繁體拼音 26 鍵、候選字列、中／英／符號切換；每次按鍵都會記住目前使用的鍵盤
- 刪除鍵在第三行最右邊，按住可連續刪除
- 長按鍵盤任何位置約 0.5 秒後拖動：左右移動游標，上下移動行
- 🎤 語音輸入；「掃描」用相機掃 Barcode／QR Code
- App 內檢查更新：打開 App 會查 GitHub 最新 Release，可下載並安裝（倉庫需為公開）
- 發佈新版：`git tag v1.2 && git push origin v1.2`，Actions 會編譯並把 APK 放進 Releases
- 簽名檔 `app/debug.keystore` 固定，所以新版可直接覆蓋安裝
- 詞庫在 `Dict.kt`，ü 用 v 輸入（nv、lv），可自行擴充
