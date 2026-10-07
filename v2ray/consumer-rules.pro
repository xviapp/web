# Gson reads unannotated configuration/storage DTO fields by their original
# names. Keep constructors and fields, including generic field signatures,
# so existing profiles, backups and Xray JSON remain compatible after R8.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep,allowoptimization class com.v2ray.ang.dto.** {
    <fields>;
    <init>(...);
}

# Enum names are part of persisted profile JSON and Gson's reflective adapter.
-keep,allowoptimization enum com.v2ray.ang.enums.** { *; }

# The pinned libv2ray AAR supplies go.** / libv2ray.** JNI keep rules.
# Capacitor and MMKV also supply their own consumer rules; do not keep the
# whole extension, UI, or dependency packages here.
