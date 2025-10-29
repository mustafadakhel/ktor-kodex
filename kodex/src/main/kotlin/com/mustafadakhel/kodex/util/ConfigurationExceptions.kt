package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.model.Realm

public abstract class ConfigurationException(
    override val message: String
) : IllegalStateException(message)

public class MissingRealmServiceException(
    realm: Realm
) : ConfigurationException("No service found for realm: $realm")

public class MissingRealmConfigException(
    realm: Realm
) : ConfigurationException("No realm configuration found for realm: $realm")

public class KodexNotConfiguredException(
    override val message: String = "Kodex not configured"
) : ConfigurationException(message)
