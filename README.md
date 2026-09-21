# AdFilter
ai写的,基于 VpnService 的 DNS 广告过滤器。设计要点是只劫持 DNS、不劫持流量，所以不用写 TCP NAT，正常上网不受影响。合并了 184,637 条公开规则（AdAway + AdGuard DNS + AdGuard 中文 + EasyList CN），异步线程加载防 ANR。
