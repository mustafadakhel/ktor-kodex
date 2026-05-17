package com.mustafadakhel.kodex.update

public enum class UserField(public val key: String) {
    EMAIL("email"),
    PHONE("phoneNumber"),
    STATUS("status"),
    PROFILE_FIRST_NAME("profile.firstName"),
    PROFILE_LAST_NAME("profile.lastName"),
    PROFILE_ADDRESS("profile.address"),
    PROFILE_PICTURE("profile.profilePicture"),
    ;

    public companion object {
        public fun customAttribute(attrKey: String): String = "customAttributes.$attrKey"
    }
}
