# Status Board

## P1
- 手机端验证服务转发：
  - 设置 → 信息转发设置：开启「推送命中到服务」
  - 确认 `callback_url` 为 `http://1.92.65.2:9508/api/SmsCallback/GKWebsetApi`
  - 点「当前本机号码」授权电话权限（用于自动获取 phoneNumber，多卡按 subscriptionId 匹配）
  - 发一条包含关键词的短信，服务端应收到 POST JSON
