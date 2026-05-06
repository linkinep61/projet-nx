package com.streamflixreborn.streamflix.adapters.viewholders

import android.content.Intent
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemProviderMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemProviderTvBinding
import com.streamflixreborn.streamflix.models.Provider
import com.streamflixreborn.streamflix.utils.MiniPlayerController
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.toActivity
import java.util.Locale

class ProviderViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private lateinit var provider: Provider

    fun bind(provider: Provider) {
        this.provider = provider

        when (_binding) {
            is ItemProviderMobileBinding -> displayMobileItem(_binding)
            is ItemProviderTvBinding -> displayTvItem(_binding)
        }
    }


    /** Switch effectif vers le provider — extrait pour être appelable après
     *  validation du PIN parental. */
    private fun performSwitch() {
        // Stop & release mini player before switching provider
        MiniPlayerController.stop()
        UserPreferences.currentProvider = provider.provider
        com.streamflixreborn.streamflix.StreamFlixApp.instance
            .refreshProviderUrlAsync(provider.provider)
        context.toActivity()?.apply {
            startActivity(
                Intent(this, this::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
            finish()
        }
    }

    private fun displayMobileItem(binding: ItemProviderMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                // 2026-05-05 : si provider verrouillé par le contrôle parental,
                // demande le PIN avant de switcher.
                if (com.streamflixreborn.streamflix.utils.ProviderLockStore
                        .isLocked(context, provider.name) &&
                    !com.streamflixreborn.streamflix.utils.ProviderLockStore
                        .isAccessible(context, provider.name)
                ) {
                    com.streamflixreborn.streamflix.ui.PinDialog.showAuth(
                        context = context,
                        title = "${provider.name} est verrouillé",
                        onSuccess = {
                            com.streamflixreborn.streamflix.utils.ProviderLockStore
                                .unlockForSession(provider.name)
                            performSwitch()
                        }
                    )
                    return@setOnClickListener
                }
                performSwitch()
            }
        }

        Glide.with(context)
            .load(provider.logo.takeIf { it.isNotEmpty() }
                ?: R.drawable.ic_provider_default_logo)
            .error(R.drawable.ic_provider_default_logo)
            .fitCenter()
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivProviderLogo)

        // 2026-05-05 : préfixe le nom avec 🔒 si verrouillé
        val isLocked = com.streamflixreborn.streamflix.utils.ProviderLockStore
            .isLocked(context, provider.name)
        binding.tvProviderName.text = if (isLocked) "🔒 ${provider.name}" else provider.name

        binding.tvProviderLanguage.text = Locale.forLanguageTag(provider.language)
            .let { it.getDisplayLanguage(it) }
            .replaceFirstChar { it.titlecase() }
    }

    private fun displayTvItem(binding: ItemProviderTvBinding) {
        binding.root.apply {
            setOnClickListener {
                // 2026-05-05 : si provider verrouillé par le contrôle parental,
                // demande le PIN avant de switcher.
                if (com.streamflixreborn.streamflix.utils.ProviderLockStore
                        .isLocked(context, provider.name) &&
                    !com.streamflixreborn.streamflix.utils.ProviderLockStore
                        .isAccessible(context, provider.name)
                ) {
                    com.streamflixreborn.streamflix.ui.PinDialog.showAuth(
                        context = context,
                        title = "${provider.name} est verrouillé",
                        onSuccess = {
                            com.streamflixreborn.streamflix.utils.ProviderLockStore
                                .unlockForSession(provider.name)
                            performSwitch()
                        }
                    )
                    return@setOnClickListener
                }
                performSwitch()
            }
        }

        Glide.with(context)
            .load(provider.logo.takeIf { it.isNotEmpty() }
                ?: R.drawable.ic_provider_default_logo)
            .error(R.drawable.ic_provider_default_logo)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivProviderLogo)

        val isLocked = com.streamflixreborn.streamflix.utils.ProviderLockStore
            .isLocked(context, provider.name)
        binding.tvProviderName.text = if (isLocked) "🔒 ${provider.name}" else provider.name

        binding.tvProviderLanguage.text = Locale.forLanguageTag(provider.language)
            .let { it.getDisplayLanguage(it) }
            .replaceFirstChar { it.titlecase() }
    }
}
