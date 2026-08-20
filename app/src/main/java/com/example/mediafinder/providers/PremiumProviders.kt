package com.example.mediafinder.providers

/**
 * Единый контракт для платных источников.
 * Каждый провайдер подключается отдельно, поэтому бесплатный поиск
 * не зависит от наличия подписки или API-доступа.
 */
interface PremiumMediaProvider {
    val id: String
    val displayName: String
    val enabled: Boolean
}

class ShutterstockProvider : PremiumMediaProvider {
    override val id = "shutterstock"
    override val displayName = "Shutterstock"
    // Станет true после настройки официального API-доступа.
    override val enabled = false
}

class AdobeStockProvider : PremiumMediaProvider {
    override val id = "adobe_stock"
    override val displayName = "Adobe Stock"
    // Станет true после настройки подходящего API-доступа.
    override val enabled = false
}

class PremiumProviderRegistry {
    val providers: List<PremiumMediaProvider> = listOf(
        ShutterstockProvider(),
        AdobeStockProvider()
    )
}
