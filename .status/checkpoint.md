# Status Board

## P1
- 手机端验证服务转发（命中 → POST JSON）：
  - 设置 → 信息转发设置：开启「推送命中到服务」
  - 接口地址：填 `http://1.92.65.2:9508/api/SmsCallback/GKWebsetApi`，点「测试连通性」确认 HTTP 2xx
  - 设备标识：可留空（默认 ANDROID_ID）或点「获取」填充
  - SIM1/SIM2：点「获取」授权电话权限；读不到则手填（部分机型/运营商限制）
  - 发一条包含关键词的短信，服务端应收到 POST JSON（phoneNumber 按短信 subscriptionId 匹配 SIM1/SIM2）
