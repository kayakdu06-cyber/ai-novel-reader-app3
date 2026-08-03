# ADR-005：应用 ID 与命名空间

- 状态：Accepted
- 日期：2026-08-01

## 决策

- Android `applicationId = "app.zhijuan.reader"`。
- Kotlin namespace 同为 `app.zhijuan.reader`。
- 产品显示名为“织卷”。

## 原因

- 名称与产品直接对应，适合单人侧载，不绑定当前机器用户名。
- `applicationId` 从第一个可保存正式数据的构建开始保持不变。

## 影响

- 如果未来发布到商店或注册正式域名，需要先确认该 ID 未被占用；在产生真实用户数据后不得仅为美观更换 ID。
- 签名证书和 applicationId 共同决定能否覆盖升级。

