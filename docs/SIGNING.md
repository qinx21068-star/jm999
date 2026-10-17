# 签名与密钥保管

正式版使用独立的发布签名（包名 `com.yigencong`，别名 `yigencong`）。**私钥丢失后无法再发布可覆盖升级的版本**，用户只能卸载重装，因此密钥保管和验证步骤都属于发布流程的一部分。

## 指纹（用于核对）

```
证书 SHA-256: 79:4B:CD:07:AA:0E:A8:F5:1A:CE:C9:F5:BC:14:77:54:8B:96:
              97:78:6E:06:F7:DC:23:6E:42:5F:1F:16:0A:94
密钥库 SHA-256: a1a96923b16189ee08d70273378b1e809b37bb62744ee0dd3291a0de29034593
```

指纹不一致的密钥**不得**用于发布。

## CI 依赖的 4 个仓库密钥

| 名称 | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库口令 |
| `ANDROID_KEY_PASSWORD` | 密钥口令 |
| `ANDROID_KEY_ALIAS` | `yigencong` |

未配置这些密钥时，Release 任务会在「Restore release keystore」步骤明确失败，不会产出未签名包。

## 备份安排

密钥材料以**加密形式**存放于私有备份仓库 `jm999-signing-backup`（`signing-backup-v1.tar.gz.enc`，AES-256-CBC + PBKDF2 600k）。加密口令由密钥库口令派生，不额外保存新秘密：

```
passphrase = sha256_hex(密钥库口令 + ":jm999-signing-backup:v1")
```

仓库内含 `RECOVERY.md`、`MANIFEST.txt` 与 `verify-signing.sh`，解压后先校验指纹再使用。

## 恢复流程

1. 取回 `signing-backup-v1.tar.gz.enc`，按上面公式派生口令并解密。
2. 运行 `verify-signing.sh release.keystore '<密钥库口令>'`，确认输出 `OK`。
3. 重新写入 4 个仓库密钥（`Settings → Secrets and variables → Actions`）。
4. 重新触发一次正式版构建验证签名可用。

## 规约

- 仓库内**不得**出现明文密钥库、口令或可解密材料；`*.keystore`/`*.jks` 已在 `.gitignore` 中。
- 不要把备份仓库改为公开。
- 更换密钥时新增 `signing-backup-v2.*`，保留旧版以便校验历史安装包。
- 建议每 6 个月做一次「解密 → 校验指纹 → 重新加密」的可恢复性演练。
