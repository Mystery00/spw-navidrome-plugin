# Navidrome 播放记录上报

## 目标与决策

按当前会话真实播放状态上报，不以列表选择、下载或提交队列代替播放。参考 Navidrome 0.64.2 `core/scrobbler/play_tracker.go` 的门槛：歌曲一半或 240 秒，取较短者。上报使用标准 Subsonic `scrobble`，开始时 `submission=false`，累计有效收听达到门槛后 `submission=true`，每轮至多一次；不再等到停止，避免关闭插件丢失已满足门槛的记录。

有效时间取正常前进的播放位置增量与单调时钟间隔的较小值，排除暂停、缓冲、跳转和长时间休眠。切歌后返回重新计数；同曲暂停继续不重置。未知时长使用 240 秒门槛。发送失败不自动重试，以免响应丢失时重复增加次数；不持久化离线上报队列。

## 实施与验收

1. `SubsonicClient` 增加上报方法；模拟 HTTP 验证字符串 ID、毫秒时间戳、submission、认证和错误处理。
2. 增加独立计时状态机，注入快照和时间；回归门槛、暂停、跳转、切歌返回、重复采样和未知时长。
3. `HostAdapter` 增加播放快照，`Host1192` 使用实机字节码核对的正在播放/位置入口，并扩充 ABI 检查。
4. `AppSession` 在宿主串行线程采样，将上报交给独立、有界网络队列。与会话代次绑定，断开取消旧请求；上报异常不影响播放或封面歌词。
5. 运行独立与宿主构建，更新使用与架构说明；实机服务器计次另行验收，不读取用户凭据或擅自安装。

参考：[Navidrome 计次实现](https://github.com/navidrome/navidrome/blob/v0.64.2/core/scrobbler/play_tracker.go)、[scrobble 协议](https://opensubsonic.netlify.app/docs/endpoints/scrobble/)。
