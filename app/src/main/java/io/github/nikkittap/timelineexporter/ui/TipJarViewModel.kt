package io.github.nikkittap.timelineexporter.ui

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-app-product IDs for the tip jar. These strings must exactly match
 * the SKUs configured in Play Console once we publish. Order here drives
 * the order shown in the dialog.
 */
private object TipProductIds {
    const val SMALL = "tip_small"
    const val MEDIUM = "tip_medium"
    const val LARGE = "tip_large"
    val ALL = listOf(SMALL, MEDIUM, LARGE)
}

/**
 * Domain DTO exposed to the UI. Avoids leaking Play Billing's [ProductDetails]
 * type across layers — VM keeps the original cached internally so it can
 * construct [BillingFlowParams] later.
 */
data class TipProduct(
    val id: String,
    val name: String,           // Localized, configured per-locale in Play Console (e.g. "Small Tip").
    val formattedPrice: String, // Localized, e.g. "$1.00" / "€1,00" / "1,00 ₽".
)

sealed interface TipJarState {
    data object Connecting : TipJarState
    /** Products fetched and ready to display. */
    data class Loaded(val products: List<TipProduct>) : TipJarState
    /** User has tapped a tip option; Google's UI is taking over. */
    data object Processing : TipJarState
    /** Purchase succeeded and was consumed. Dialog shows a thank-you. */
    data object ThankYou : TipJarState
    /** Device doesn't support Google Play Billing (no Play Services, etc.). */
    data object Unavailable : TipJarState
    /** Anything else went wrong. [technicalMessage] is BillingResult.debugMessage. */
    data class Error(val technicalMessage: String?) : TipJarState
}

class TipJarViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<TipJarState>(TipJarState.Connecting)
    val state: StateFlow<TipJarState> = _state.asStateFlow()

    /** Keep the original [ProductDetails] so we can launch the billing flow later. */
    private val productDetailsCache = mutableMapOf<String, ProductDetails>()

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isNullOrEmpty()) {
                    // Shouldn't happen with OK, but handle defensively.
                    resetToLoaded()
                } else {
                    purchases.forEach(::consumePurchase)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                // Quietly drop back to product list. Not an error.
                resetToLoaded()
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // Previous tip wasn't consumed (e.g. app killed mid-flow).
                // Treat as success — consume the outstanding purchase.
                purchases?.forEach(::consumePurchase) ?: resetToLoaded()
            }
            else -> {
                Log.w(TAG, "Purchase failed: ${result.responseCode} ${result.debugMessage}")
                _state.value = TipJarState.Error(result.debugMessage)
            }
        }
    }

    private var billingClient: BillingClient? = null

    init {
        connect()
    }

    /**
     * Establish or re-establish the BillingClient connection and query
     * products. Safe to call multiple times — UI exposes this as "retry".
     */
    fun connect() {
        if (billingClient?.isReady == true) {
            queryProducts()
            return
        }
        _state.value = TipJarState.Connecting

        billingClient = BillingClient.newBuilder(getApplication())
            .setListener(purchasesListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()

        billingClient!!.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK -> queryProducts()
                    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
                        _state.value = TipJarState.Unavailable
                    else ->
                        _state.value = TipJarState.Error(result.debugMessage)
                }
            }
            override fun onBillingServiceDisconnected() {
                // Connection dropped. Stay in current state; user can press
                // retry in the Error path, which calls connect() again.
                Log.d(TAG, "Billing service disconnected")
            }
        })
    }

    private fun queryProducts() {
        val productQueries = TipProductIds.ALL.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productQueries)
            .build()

        // Billing 7.1.x callback signature is still (BillingResult,
        // List<ProductDetails>?); QueryProductDetailsResult is a later
        // 7.x/8.x addition we don't depend on.
        billingClient?.queryProductDetailsAsync(params) { result, productDetailsList ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                _state.value = TipJarState.Error(result.debugMessage)
                return@queryProductDetailsAsync
            }
            val details: List<ProductDetails> = productDetailsList ?: emptyList()
            productDetailsCache.clear()
            details.forEach { detail -> productDetailsCache[detail.productId] = detail }

            // Preserve the order defined in TipProductIds.ALL, regardless
            // of the order Play returned. Drop any IDs Play didn't recognise.
            val ordered = TipProductIds.ALL.mapNotNull { id -> productDetailsCache[id]?.toDto() }
            _state.value = TipJarState.Loaded(ordered)
        }
    }

    /**
     * Launch the Play Billing flow for the given product. UI must pass an
     * Activity reference (the ComponentActivity hosting the Compose tree).
     */
    fun launchPurchase(activity: Activity, productId: String) {
        val details = productDetailsCache[productId] ?: run {
            _state.value = TipJarState.Error("Product not loaded: $productId")
            return
        }
        _state.value = TipJarState.Processing

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        val result = billingClient?.launchBillingFlow(activity, params)
        if (result?.responseCode != BillingClient.BillingResponseCode.OK) {
            _state.value = TipJarState.Error(result?.debugMessage)
        }
    }

    private fun consumePurchase(purchase: Purchase) {
        val params = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient?.consumeAsync(params) { result, _ ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _state.value = TipJarState.ThankYou
            } else {
                Log.w(TAG, "Consume failed: ${result.responseCode} ${result.debugMessage}")
                // Treat as success anyway — purchase succeeded, the only
                // downside is the user might see ITEM_ALREADY_OWNED next time
                // (which we handle in purchasesListener above).
                _state.value = TipJarState.ThankYou
            }
        }
    }

    /**
     * After ThankYou or Error, called by UI when user wants to try again
     * or just dismiss the message — returns to Loaded if we have products,
     * otherwise re-queries.
     */
    fun resetToLoaded() {
        val cached = productDetailsCache.values.toList()
        if (cached.isNotEmpty()) {
            val ordered = TipProductIds.ALL.mapNotNull { id ->
                productDetailsCache[id]?.toDto()
            }
            _state.value = TipJarState.Loaded(ordered)
        } else {
            queryProducts()
        }
    }

    override fun onCleared() {
        super.onCleared()
        billingClient?.endConnection()
        billingClient = null
    }

    companion object {
        private const val TAG = "TipJarVM"
    }
}

/**
 * Convert Play Billing's ProductDetails (which carries a lot more than we
 * need) to our minimal UI DTO. INAPP one-time-purchase products always
 * have exactly one [ProductDetails.OneTimePurchaseOfferDetails].
 */
private fun ProductDetails.toDto(): TipProduct = TipProduct(
    id = productId,
    name = name,
    formattedPrice = oneTimePurchaseOfferDetails?.formattedPrice ?: "—",
)
