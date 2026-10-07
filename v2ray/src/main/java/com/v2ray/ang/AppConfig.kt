package com.v2ray.ang


object AppConfig {

    /** The application's package name. */
    const val ANG_PACKAGE = BuildConfig.APPLICATION_ID
    const val TAG = BuildConfig.APPLICATION_ID

    /** Directory names used in the app's file system. */
    const val DIR_ASSETS = "assets"

    // Default subscription ID for ungrouped servers
    const val DEFAULT_SUBSCRIPTION_ID = "__default_subscription__"

    /** Preferences mapped to MMKV storage. */
    const val PREF_SNIFFING_ENABLED = "pref_sniffing_enabled"
    const val PREF_ROUTE_ONLY_ENABLED = "pref_route_only_enabled"
    const val PREF_ROUTING_DOMAIN_STRATEGY = "pref_routing_domain_strategy"
    const val PREF_ROUTING_RULESET = "pref_routing_ruleset"
    const val PREF_MUX_ENABLED = "pref_mux_enabled"
    const val PREF_MUX_CONCURRENCY = "pref_mux_concurrency"
    const val PREF_MUX_XUDP_CONCURRENCY = "pref_mux_xudp_concurrency"
    const val PREF_MUX_XUDP_QUIC = "pref_mux_xudp_quic"
    const val PREF_FRAGMENT_ENABLED = "pref_fragment_enabled"
    const val PREF_FRAGMENT_PACKETS = "pref_fragment_packets"
    const val PREF_FRAGMENT_LENGTH = "pref_fragment_length"
    const val PREF_FRAGMENT_INTERVAL = "pref_fragment_interval"
    const val PREF_FRAGMENT_MAXSPLIT = "pref_fragment_maxsplit"
    const val PREF_OBSERVATORY_LEAST_PING_INTERVAL = "pref_observatory_least_ping_interval"
    const val PREF_OBSERVATORY_LEAST_LOAD_INTERVAL = "pref_observatory_least_load_interval"
    const val PREF_OBSERVATORY_LEAST_LOAD_METHOD = "pref_observatory_least_load_method"
    const val PREF_OBSERVATORY_LEAST_LOAD_SAMPLING = "pref_observatory_least_load_sampling"
    const val PREF_OBSERVATORY_LEAST_LOAD_TIMEOUT = "pref_observatory_least_load_timeout"
    const val SUBSCRIPTION_UPDATE_TASK_NAME = "subscription_updater"
    const val SUBSCRIPTION_MIN_INTERVAL_MINUTES = 15L
    const val PREF_CONFIRM_REMOVE = "pref_confirm_remove"
    const val PREF_DOUBLE_COLUMN_DISPLAY = "pref_double_column_display"
    const val PREF_GROUP_ALL_DISPLAY = "pref_group_all_display"
    const val PREF_IPV6_ENABLED = "pref_ipv6_enabled"
    const val PREF_PREFER_IPV6 = "pref_prefer_ipv6"
    const val PREF_REMOTE_DNS = "pref_remote_dns"
    const val PREF_DOMESTIC_DNS = "pref_domestic_dns"
    const val PREF_DNS_HOSTS = "pref_dns_hosts"
    const val PREF_DELAY_TEST_URL = "pref_delay_test_url"
    const val PREF_IP_API_URL = "pref_ip_api_url"
    const val PREF_LOGLEVEL = "pref_core_loglevel"
    const val PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD = "pref_outbound_domain_resolve_method"
    const val PREF_GEO_FILES_SOURCES = "pref_geo_files_sources"
    const val PREF_UPDATE_SUBSCRIPTION = "pref_update_subscription"
    const val PREF_AUTO_TEST_AFTER_UPDATE_SUBSCRIPTION = "pref_auto_test_after_update_subscription"
    const val PREF_AUTO_REMOVE_INVALID_AFTER_TEST = "pref_auto_remove_invalid_after_test"
    const val PREF_AUTO_SORT_AFTER_TEST = "pref_auto_sort_after_test"
    const val PREF_REAL_PING_CONCURRENCY = "pref_real_ping_concurrency"

    /** Cache keys. */
    const val CACHE_SUBSCRIPTION_ID = "cache_subscription_id"

/** Broadcast actions. */
    const val BROADCAST_ACTION_SERVICE = "$ANG_PACKAGE.action.service"
    const val BROADCAST_ACTION_ACTIVITY = "$ANG_PACKAGE.action.activity"

/** Tags for different proxy modes. */
    const val TAG_PROXY = "proxy"
    const val TAG_DIRECT = "direct"
    const val TAG_BLOCKED = "block"
    const val TAG_DNS = "dns-module"
    const val TAG_DOMESTIC_DNS = "domestic-dns"
    const val TAG_BALANCER = "balancer-main"
    const val TAG_BALANCER_PRE = "balancer"

    /** URLs for various resources. */
    const val GITHUB_URL = "https://github.com"
    const val GITHUB_RAW_URL = "https://raw.githubusercontent.com"
    const val GITHUB_DOWNLOAD_URL = "$GITHUB_URL/%s/releases/latest/download"
    const val DELAY_TEST_URL = "https://www.gstatic.com/generate_204"
    const val DELAY_TEST_URL2 = "https://www.google.com/generate_204"
    const val OBSERVATORY_LEAST_PING_INTERVAL = "3m"
    const val OBSERVATORY_LEAST_LOAD_INTERVAL = "5m"
    const val OBSERVATORY_LEAST_LOAD_METHOD = "HEAD"
    const val OBSERVATORY_LEAST_LOAD_SAMPLING = "2"
    const val OBSERVATORY_LEAST_LOAD_TIMEOUT = "30s"

    //    const val IP_API_URL = "https://speed.cloudflare.com/meta"
    const val IP_API_URL = "https://api.ip.sb/geoip"

    /** DNS server addresses. */
    const val DNS_PROXY = "https://cloudflare-dns.com/dns-query"
    const val DNS_DIRECT = "223.5.5.5"
    const val GEOSITE_CN = "geosite:cn"
    const val GEOIP_PRIVATE = "geoip:private"
    const val GEOIP_CN = "geoip:cn"

    /** Geo data file names. */
    const val GEOSITE_DAT = "geosite.dat"
    const val GEOIP_DAT = "geoip.dat"
    const val GEOIP_ONLY_CN_PRIVATE_DAT = "geoip-only-cn-private.dat"
    const val GEOIP_ONLY_CN_PRIVATE_URL = "$GITHUB_RAW_URL/Loyalsoldier/geoip/release/$GEOIP_ONLY_CN_PRIVATE_DAT"

    /** Ports and addresses for various services. */
    const val WIREGUARD_LOCAL_ADDRESS_V4 = "172.16.0.2/32"
    const val WIREGUARD_LOCAL_MTU = "1420"
    const val WIREGUARD_LOCAL_REMOTE_DNS = "1.1.1.1,1.0.0.1,2606:4700:4700::1111,2606:4700:4700::1001"
    const val LOOPBACK = "127.0.0.1"

    /** Shared defaults for settings shown in the UI and consumed by config generation. */
    const val DEFAULT_OUTBOUND_DOMAIN_RESOLVE_METHOD = "1"
    const val DEFAULT_MUX_XUDP_CONCURRENCY = "8"

    /** Message constants for communication. */
    const val MSG_REGISTER_CLIENT = 1
    const val MSG_STATE_RUNNING = 11
    const val MSG_STATE_NOT_RUNNING = 12
    const val MSG_UNREGISTER_CLIENT = 2
    const val MSG_STATE_START = 3
    const val MSG_STATE_START_SUCCESS = 31
    const val MSG_STATE_START_FAILURE = 32
    const val MSG_STATE_STOP = 4
    const val MSG_STATE_STOP_SUCCESS = 41
    const val MSG_STATE_RESTART = 5
    const val MSG_MEASURE_DELAY = 6
    const val MSG_MEASURE_DELAY_RESULT = 61
    const val MSG_MEASURE_DELAY_CANCEL = 62
    const val MSG_MEASURE_CONFIG_START = 7
    const val MSG_MEASURE_CONFIG_CANCEL = 71
    const val MSG_MEASURE_CONFIG_SUCCESS = 72
    const val MSG_MEASURE_CONFIG_NOTIFY = 73
    const val MSG_MEASURE_CONFIG_FINISH = 74

    const val MSG_SUB_UPDATE_START = 8
    const val MSG_SUB_UPDATE_CANCEL = 81

    /** Protocols Scheme **/
    const val VMESS = "vmess://"
    const val CUSTOM = ""
    const val SHADOWSOCKS = "ss://"
    const val SOCKS = "socks://"
    const val SOCKS4 = "socks4://"
    const val SOCKS5 = "socks5://"
    const val HTTP = "http://"
    const val VLESS = "vless://"
    const val TROJAN = "trojan://"
    const val WIREGUARD = "wireguard://"
    const val TUIC = "tuic://"
    const val HYSTERIA = "hysteria://"
    const val HYSTERIA2 = "hysteria2://"
    const val HY2 = "hy2://"
    const val V2RAYNFMTS = "v2rayn://"

    // Google API rule constants
    const val GOOGLEAPIS_CN_DOMAIN = "domain:googleapis.cn"
    const val GOOGLEAPIS_COM_DOMAIN = "googleapis.com"

    // Android Private DNS constants
    const val DNS_ALIDNS_DOMAIN = "dns.alidns.com"
    const val DNS_CISCO_SSE_DOMAIN = "dns.sse.cisco.com"
    const val DNS_CISCO_UMBRELLA_DOMAIN = "dns.umbrella.com"
    const val DNS_CLOUDFLARE_ONE_DOMAIN = "one.one.one.one"
    const val DNS_CLOUDFLARE_ONEDOT_DNS_DOMAIN = "1dot1dot1dot1.cloudflare-dns.com"
    const val DNS_CLOUDFLARE_DNS_COM_DOMAIN = "dns.cloudflare.com"
    const val DNS_CLOUDFLARE_DNS_DOMAIN = "cloudflare-dns.com"
    const val DNS_CLOUDFLARE_WARP_DOMAIN = "engage.cloudflareclient.com"
    const val DNS_DNSPOD_DOH_DOMAIN = "doh.pub"
    const val DNS_DNSPOD_DOT_DOMAIN = "dot.pub"
    const val DNS_GOOGLE_DOMAIN = "dns.google"
    const val DNS_QUAD9_DOMAIN = "dns.quad9.net"
    const val DNS_SB_DOMAIN = "dns.sb"
    const val DNS_YANDEX_DOMAIN = "common.dot.dns.yandex.net"

    const val DEFAULT_PORT = 443
    const val DEFAULT_SECURITY = "auto"
    const val DEFAULT_LEVEL = 8
    const val DEFAULT_NETWORK = "tcp"
    const val TLS = "tls"
    const val REALITY = "reality"
    const val HEADER_TYPE_HTTP = "http"

    const val UNIDENTIFIED_PACKAGE = "__unknown_app__"

    val DNS_ALIDNS_ADDRESSES = arrayListOf("223.5.5.5", "223.6.6.6", "2400:3200::1", "2400:3200:baba::1")
    val DNS_CISCO_SSE_ADDRESSES = arrayListOf("208.67.220.220", "208.67.222.222", "2620:119:35::35", "2620:119:53::53")
    val DNS_CISCO_UMBRELLA_ADDRESSES = arrayListOf("208.67.220.220", "208.67.222.222", "2620:119:35::35", "2620:119:53::53")
    val DNS_CLOUDFLARE_ONE_ADDRESSES = arrayListOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
    val DNS_CLOUDFLARE_ONEDOT_DNS_ADDRESSES = arrayListOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
    val DNS_CLOUDFLARE_DNS_COM_ADDRESSES = arrayListOf("162.159.61.8", "172.64.41.8", "2a06:98c1:52::8", "2803:f800:53::8")
    val DNS_CLOUDFLARE_DNS_ADDRESSES = arrayListOf("104.16.248.249", "104.16.249.249", "2606:4700::6810:f8f9", "2606:4700::6810:f9f9")
    val DNS_CLOUDFLARE_WARP_ADDRESSES = arrayListOf("162.159.192.1", "2606:4700:d0::a29f:c001")
    val DNS_DNSPOD_DOH_ADDRESSES = arrayListOf("1.12.12.12", "120.53.53.53")
    val DNS_DNSPOD_DOT_ADDRESSES = arrayListOf("1.12.12.12", "120.53.53.53")
    val DNS_GOOGLE_ADDRESSES = arrayListOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
    val DNS_QUAD9_ADDRESSES = arrayListOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")
    val DNS_SB_ADDRESSES = arrayListOf("45.11.45.11", "185.222.222.222", "2a09::", "2a11::")
    val DNS_YANDEX_ADDRESSES = arrayListOf("77.88.8.8", "77.88.8.1", "2a02:6b8::feed:0ff", "2a02:6b8:0:1::feed:0ff")

    val PRIVATE_IP_LIST = arrayListOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "127.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "169.254.0.0/16",
        "224.0.0.0/4"
    )

    val GEO_FILES_SOURCES = arrayListOf(
        "Loyalsoldier/v2ray-rules-dat"
    )

    val BUILTIN_OUTBOUND_TAGS = setOf(
        TAG_PROXY,
        TAG_DIRECT,
        TAG_BLOCKED,
    )

    val OBSERVATORY_DURATION_PATTERN = Regex("""[1-9]\d*(ms|s|m|h)""")
}
