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
        // 2026-05-12 (user "il faut remettre le home avec les sources au tout
        // début qu'on puisse y revenir à tout moment pour changer de source") :
        // pour MyIptvProvider, on ouvre TOUJOURS IptvSourcesActivity au clic
        // sur le provider (peu importe s'il y a des sources ou pas). L'user
        // clique ensuite sur la source qu'il veut → MainActivity avec le
        // contenu. Back depuis MainActivity → retour ici. Comme ça il peut
        // changer de source à tout moment sans clear app data.
        if (provider.provider is com.streamflixreborn.streamflix.providers.MyIptvProvider) {
            // 2026-06-09 (user "on peut pas faire durer la radio dans tous les
            //   providers jusqu'à tant qu'on ait trouvé quelque chose") : ne
            //   stoppe le mini-player QUE si ce n'est PAS une radio.
            if (!MiniPlayerController.isRadioChannel(MiniPlayerController.currentChannelId)) {
                MiniPlayerController.stop()
            }
            UserPreferences.currentProvider = provider.provider
            // 2026-05-13 (user "enlève cet écran on la met dans les paramètres") :
            // le clic sur Mon IPTV va TOUJOURS direct à la home — pas d'IptvSourcesActivity
            // intermédiaire. La gestion des sources est dans Paramètres → Paramètres
            // du fournisseur → Mon IPTV.
            context.toActivity()?.apply {
                val isTv = com.streamflixreborn.streamflix.BuildConfig.APP_LAYOUT == "tv" ||
                    (com.streamflixreborn.streamflix.BuildConfig.APP_LAYOUT != "mobile" &&
                        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK))
                val target = if (isTv) {
                    com.streamflixreborn.streamflix.activities.main.MainTvActivity::class.java
                } else {
                    com.streamflixreborn.streamflix.activities.main.MainMobileActivity::class.java
                }
                startActivity(Intent(this, target))
            }
            return
        }
        // Stop & release mini player before switching provider
        // 2026-06-09 : sauf si c'est une radio (laisse jouer en arrière-plan).
        if (!MiniPlayerController.isRadioChannel(MiniPlayerController.currentChannelId)) {
            MiniPlayerController.stop()
        }
        // 2026-05-26 : vider le cache AnimeSama AVANT le restart activité
        // pour forcer un vrai getHome() + CF bypass au retour.
        try { com.streamflixreborn.streamflix.providers.AnimeSamaProvider.resetState() } catch (_: Throwable) {}
        // 2026-06-09 (user "les cookies CF doivent rester, le home doit se
        //   recharger, vider à la fermeture") : pareil pour DessinAnime.
        try { com.streamflixreborn.streamflix.providers.DessinAnimeProvider.resetState() } catch (_: Throwable) {}
        UserPreferences.currentProvider = provider.provider
        // 2026-06-09 : si l'user a sélectionné DessinAnime, déclencher le
        //   bypass CF immédiatement (dialog s'affiche direct au lieu d'attendre
        //   le 1er fetch du home).
        // 2026-06-13 v3 (user "le Home ne charge pas tout seul mais quand on
        //   va dans film ça charge bien") : le dialog WebView CF (silent=false)
        //   masque le home au boot → confirmé que c'est bien ce prefetch qui
        //   bloque. RE-DÉSACTIVÉ. Le bypass se déclenchera au 1er fetch détail
        //   (clic sur une carte → getMovie/getTvShow → ensureCfBypassed).
        // if (provider.provider.name == "DessinAnime") {
        //     try { com.streamflixreborn.streamflix.providers.DessinAnimeProvider.prefetchCfBypassIfNeeded() } catch (_: Throwable) {}
        // }
        com.streamflixreborn.streamflix.StreamFlixApp.instance
            .refreshProviderUrlAsync(provider.provider)
        // 2026-07-12 : signaler au onDestroy que c'est un switch provider,
        //   PAS un close d'app → ne pas stopper la radio.
        MiniPlayerController.isProviderSwitching = true
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
            // 2026-06-13 (porté upstream Favorite Providers) : long-press
            //   toggle le provider dans la liste des favoris + affiche un
            //   toast + met à jour l'étoile + notifie le picker langue.
            setOnLongClickListener {
                val nowFavorite = UserPreferences.toggleFavoriteProvider(provider.name)
                binding.ivFavoriteStar.visibility = if (nowFavorite) android.view.View.VISIBLE
                    else android.view.View.GONE
                android.widget.Toast.makeText(
                    context,
                    if (nowFavorite) "${provider.name} ajouté aux favoris"
                        else "${provider.name} retiré des favoris",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                true
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

        // 2026-06-13 : affiche l'étoile si provider favori.
        binding.ivFavoriteStar.visibility = if (UserPreferences.isFavoriteProvider(provider.name))
            android.view.View.VISIBLE else android.view.View.GONE
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
            // 2026-06-13 (porté upstream Favorite Providers) : long-press D-pad
            //   sur la TV pour ajouter/retirer le provider des favoris.
            setOnLongClickListener {
                val nowFavorite = UserPreferences.toggleFavoriteProvider(provider.name)
                binding.ivFavoriteStar.visibility = if (nowFavorite) android.view.View.VISIBLE
                    else android.view.View.GONE
                android.widget.Toast.makeText(
                    context,
                    if (nowFavorite) "${provider.name} ajouté aux favoris"
                        else "${provider.name} retiré des favoris",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                true
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

        // 2026-06-13 : affiche l'étoile si provider favori.
        binding.ivFavoriteStar.visibility = if (UserPreferences.isFavoriteProvider(provider.name))
            android.view.View.VISIBLE else android.view.View.GONE
    }
}
