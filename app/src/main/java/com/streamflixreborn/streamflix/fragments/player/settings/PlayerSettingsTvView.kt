package com.streamflixreborn.streamflix.fragments.player.settings

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemSettingTvBinding
import com.streamflixreborn.streamflix.databinding.ViewPlayerSettingsTvBinding
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.margin
import com.streamflixreborn.streamflix.utils.UserPreferences

class PlayerSettingsTvView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : PlayerSettingsView(context, attrs, defStyleAttr) {

    val binding = ViewPlayerSettingsTvBinding.inflate(
        LayoutInflater.from(context),
        this,
        true
    )

    private val settingsAdapter = SettingsAdapter(this, Settings.listTvForCurrentProvider())
    private val qualityAdapter = SettingsAdapter(this, Settings.Quality.list)
    private val audioAdapter = SettingsAdapter(this, Settings.Audio.list)
    private val subtitlesAdapter = SettingsAdapter(this, Settings.Subtitle.list)
    private val captionStyleAdapter = SettingsAdapter(this, Settings.Subtitle.Style.list)
    private val fontColorAdapter = SettingsAdapter(this, Settings.Subtitle.Style.FontColor.list)
    private val textSizeAdapter = SettingsAdapter(this, Settings.Subtitle.Style.TextSize.list)
    private val fontOpacityAdapter = SettingsAdapter(this, Settings.Subtitle.Style.FontOpacity.list)
    private val edgeStyleAdapter = SettingsAdapter(this, Settings.Subtitle.Style.EdgeStyle.list)
    private val backgroundColorAdapter = SettingsAdapter(this, Settings.Subtitle.Style.BackgroundColor.list)
    private val backgroundOpacityAdapter = SettingsAdapter(this, Settings.Subtitle.Style.BackgroundOpacity.list)
    private val windowColorAdapter = SettingsAdapter(this, Settings.Subtitle.Style.WindowColor.list)
    private val windowOpacityAdapter = SettingsAdapter(this, Settings.Subtitle.Style.WindowOpacity.list)
    private val openSubtitlesAdapter = SettingsAdapter(this, Settings.Subtitle.OpenSubtitles.list)
    private val subDLAdapter = SettingsAdapter(this, Settings.Subtitle.SubDLSubtitles.list)
    private val speedAdapter = SettingsAdapter(this, Settings.Speed.list)
    private val extraBufferingAdapter = SettingsAdapter(this, Settings.ExtraBuffering.list)
    private val softwareDecoderAdapter = SettingsAdapter(this, Settings.SoftwareDecoder.list)
    private val channelVariantAdapter = SettingsAdapter(this, Settings.ChannelVariant.list)
    private val serversAdapter = SettingsAdapter(this, Settings.Server.list)
    private val marginAdapter = SettingsAdapter(this, Settings.Subtitle.Style.Margin.list)

    override var onSubtitlesClicked: (() -> Unit)? = null
    var onManualZoomClicked: (() -> Unit)? = null
    var onDownloadsClicked: (() -> Unit)? = null

    init {
        binding.rvSettings.addItemDecoration(SpacingItemDecoration(6.dp(context)))
        binding.btnSettingsDownloads.setOnClickListener {
            hide()
            onDownloadsClicked?.invoke()
        }
        // D-pad: allow navigating UP from the list to the downloads button
        binding.rvSettings.nextFocusUpId = R.id.btn_settings_downloads
        binding.btnSettingsDownloads.nextFocusDownId = R.id.rv_settings
    }

    fun onBackPressed(): Boolean {
        when (currentSettings) {
            Setting.MAIN -> hide()
            Setting.QUALITY,
            Setting.AUDIO,
            Setting.SUBTITLES,
            Setting.SPEED,
            Setting.CHANNEL_VARIANT,
            Setting.EXTRA_BUFFERING,
            Setting.SOFTWARE_DECODER,
            Setting.SERVERS,
            Setting.GESTURES,
            Setting.KEEP_SCREEN_ON,
            Setting.MANUAL_ZOOM -> displaySettings(Setting.MAIN)
            Setting.CAPTION_STYLE -> displaySettings(Setting.SUBTITLES)
            Setting.CAPTION_STYLE_FONT_COLOR,
            Setting.CAPTION_STYLE_TEXT_SIZE,
            Setting.CAPTION_STYLE_FONT_OPACITY,
            Setting.CAPTION_STYLE_EDGE_STYLE,
            Setting.CAPTION_STYLE_BACKGROUND_COLOR,
            Setting.CAPTION_STYLE_BACKGROUND_OPACITY,
            Setting.CAPTION_STYLE_WINDOW_COLOR,
            Setting.CAPTION_STYLE_WINDOW_OPACITY,
            Setting.CAPTION_STYLE_MARGIN -> displaySettings(Setting.CAPTION_STYLE)
            Setting.OPEN_SUBTITLES -> displaySettings(Setting.SUBTITLES)
            Setting.SUBDL -> displaySettings(Setting.SUBTITLES)
        }
        return true
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        if (binding.rvSettings.hasFocus()) {
            // UP → explicitly go to downloads button when at top of list
            if (direction == View.FOCUS_UP) {
                val lm = binding.rvSettings.layoutManager
                    as? androidx.recyclerview.widget.LinearLayoutManager
                val firstPos = lm?.findFirstCompletelyVisibleItemPosition() ?: 0
                if (firstPos == 0) {
                    return binding.btnSettingsDownloads
                }
            }
            // LEFT/RIGHT → allow navigation within items (row ↔ favorite/ban buttons)
            if (direction == View.FOCUS_RIGHT || direction == View.FOCUS_LEFT) {
                return super.focusSearch(focused, direction) ?: focused
            }
            // Block DOWN escape from the list
            return focused
        }
        return super.focusSearch(focused, direction) ?: focused
    }


    fun show() {
        this.visibility = View.VISIBLE

        displaySettings(Setting.MAIN)
    }

    private fun displaySettings(setting: Setting) {
        currentSettings = setting

        if (setting == Setting.SUBTITLES) {
            onSubtitlesClicked?.invoke()
        }

        binding.tvSettingsHeader.apply {
            text = when (setting) {
                Setting.MAIN -> context.getString(R.string.player_settings_title)
                Setting.QUALITY -> context.getString(R.string.player_settings_quality_title)
                Setting.AUDIO -> context.getString(R.string.player_settings_audio_title)
                Setting.SUBTITLES -> context.getString(R.string.player_settings_subtitles_title)
                Setting.CAPTION_STYLE -> context.getString(R.string.player_settings_caption_style_title)
                Setting.CAPTION_STYLE_FONT_COLOR -> context.getString(R.string.player_settings_caption_style_font_color_title)
                Setting.CAPTION_STYLE_TEXT_SIZE -> context.getString(R.string.player_settings_caption_style_text_size_title)
                Setting.CAPTION_STYLE_FONT_OPACITY -> context.getString(R.string.player_settings_caption_style_font_opacity_title)
                Setting.CAPTION_STYLE_EDGE_STYLE -> context.getString(R.string.player_settings_caption_style_edge_style_title)
                Setting.CAPTION_STYLE_BACKGROUND_COLOR -> context.getString(R.string.player_settings_caption_style_background_color_title)
                Setting.CAPTION_STYLE_BACKGROUND_OPACITY -> context.getString(R.string.player_settings_caption_style_background_opacity_title)
                Setting.CAPTION_STYLE_WINDOW_COLOR -> context.getString(R.string.player_settings_caption_style_window_color_title)
                Setting.CAPTION_STYLE_WINDOW_OPACITY -> context.getString(R.string.player_settings_caption_style_window_opacity_title)
                Setting.OPEN_SUBTITLES -> context.getString(R.string.player_settings_open_subtitles_title)
                Setting.SUBDL -> context.getString(R.string.player_settings_subdl_title)
                Setting.SPEED -> context.getString(R.string.player_settings_speed_title)
                Setting.CHANNEL_VARIANT -> context.getString(R.string.player_settings_channel_variant_title)
                Setting.EXTRA_BUFFERING -> context.getString(R.string.player_settings_extra_buffer_title)
                Setting.SOFTWARE_DECODER -> context.getString(R.string.player_settings_software_decoder_title)
                Setting.SERVERS -> context.getString(R.string.player_settings_servers_title)
                Setting.CAPTION_STYLE_MARGIN -> context.getString(R.string.player_settings_caption_style_margin_title)
                Setting.GESTURES -> context.getString(R.string.player_settings_gestures_title)
                Setting.KEEP_SCREEN_ON -> context.getString(R.string.player_settings_keep_screen_on_title)
                Setting.MANUAL_ZOOM -> context.getString(R.string.player_settings_manual_zoom_label)
            }
        }

        binding.rvSettings.adapter = when (setting) {
            Setting.MAIN -> settingsAdapter
            Setting.QUALITY -> qualityAdapter
            Setting.AUDIO -> audioAdapter
            Setting.SUBTITLES -> subtitlesAdapter
            Setting.CAPTION_STYLE -> captionStyleAdapter
            Setting.CAPTION_STYLE_FONT_COLOR -> fontColorAdapter
            Setting.CAPTION_STYLE_TEXT_SIZE -> textSizeAdapter
            Setting.CAPTION_STYLE_FONT_OPACITY -> fontOpacityAdapter
            Setting.CAPTION_STYLE_EDGE_STYLE -> edgeStyleAdapter
            Setting.CAPTION_STYLE_BACKGROUND_COLOR -> backgroundColorAdapter
            Setting.CAPTION_STYLE_BACKGROUND_OPACITY -> backgroundOpacityAdapter
            Setting.CAPTION_STYLE_WINDOW_COLOR -> windowColorAdapter
            Setting.CAPTION_STYLE_WINDOW_OPACITY -> windowOpacityAdapter
            Setting.OPEN_SUBTITLES -> openSubtitlesAdapter
            Setting.SUBDL -> subDLAdapter
            Setting.SPEED -> speedAdapter
            Setting.CHANNEL_VARIANT -> channelVariantAdapter
            Setting.EXTRA_BUFFERING -> extraBufferingAdapter
            Setting.SOFTWARE_DECODER -> softwareDecoderAdapter
            Setting.SERVERS -> serversAdapter
            Setting.CAPTION_STYLE_MARGIN -> marginAdapter
            else -> settingsAdapter
        }
        binding.rvSettings.requestFocus()
    }

    override fun onServerListUpdated() {
        refreshServerList()
    }

    fun refreshServerList() {
        serversAdapter.notifyDataSetChanged()
        settingsAdapter.notifyDataSetChanged()
    }

    fun refreshChannelVariantList() {
        // Sort variants by quality (ascending), then by name. Lower-quality streams come
        // first so auto-failover tries the lightest feeds before 4K — avoids freezes.
        val list = PlayerSettingsView.Settings.ChannelVariant.list
        if (list.size > 1) {
            val sorted = list.sortedWith(compareBy({ qualityRank(it.name) }, { it.name.lowercase() }))
            list.clear()
            list.addAll(sorted)
        }
        channelVariantAdapter.notifyDataSetChanged()
        settingsAdapter.notifyDataSetChanged()
    }

    private fun qualityRank(label: String): Int {
        val l = label.uppercase()
        return when {
            "360P" in l -> 0
            "480P" in l || "SD" in l -> 1
            "720P" in l -> 2
            "FHD" in l -> 4
            "UHD" in l || "4K" in l -> 5
            "HD" in l -> 3
            else -> 2
        }
    }

    fun hide() {
        this.visibility = View.GONE
    }


    private class SettingsAdapter(
        private val settingsView: PlayerSettingsTvView,
        private val items: List<Item>,
    ) : RecyclerView.Adapter<SettingViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            SettingViewHolder(
                settingsView,
                ItemSettingTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

        override fun onBindViewHolder(holder: SettingViewHolder, position: Int) {
            holder.displaySettings(items[position])
        }

        override fun getItemCount() = items.size
    }

    private class SettingViewHolder(
        private val settingsView: PlayerSettingsTvView,
        private val binding: ItemSettingTvBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun displaySettings(item: Item) {
            binding.root.apply {
                when (item) {
                    Settings.Subtitle.Style,
                    Settings.Subtitle.Style.ResetStyle -> margin(bottom = 6.dp(context))
                    Settings.Subtitle.LocalSubtitles -> margin(top = 6.dp(context))
                    else -> margin(bottom = 0, top = 0)
                }
                setOnClickListener {
                    when (item) {
                        is Settings -> {
                            when (item) {
                                Settings.Quality -> settingsView.displaySettings(Setting.QUALITY)
                                Settings.Audio -> settingsView.displaySettings(Setting.AUDIO)
                                Settings.Subtitle -> settingsView.displaySettings(Setting.SUBTITLES)
                                Settings.Speed -> settingsView.displaySettings(Setting.SPEED)
                                Settings.ExtraBuffering -> settingsView.displaySettings(Setting.EXTRA_BUFFERING)
                                Settings.SoftwareDecoder -> settingsView.displaySettings(Setting.SOFTWARE_DECODER)
                                Settings.Server -> settingsView.displaySettings(Setting.SERVERS)
                                Settings.ChannelVariant -> settingsView.displaySettings(Setting.CHANNEL_VARIANT)
                                Settings.ManualZoom -> {
                                    settingsView.onManualZoomClicked?.invoke()
                                    settingsView.hide()
                                }
                                else -> {}
                            }
                        }

                        is Settings.Quality -> {
                            settingsView.onQualitySelected.invoke(item)
                            settingsView.hide()
                        }

                        is Settings.Audio -> {
                            settingsView.onAudioSelected.invoke(item)
                            settingsView.hide()
                        }

                        is Settings.Subtitle -> {
                            when (item) {
                                Settings.Subtitle.Style -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE)
                                }

                                is Settings.Subtitle.None,
                                is Settings.Subtitle.TextTrackInformation -> {
                                    settingsView.onSubtitleSelected.invoke(item)
                                    settingsView.hide()
                                }

                                Settings.Subtitle.LocalSubtitles -> {
                                    settingsView.onLocalSubtitlesClicked?.invoke()
                                    settingsView.hide()
                                }

                                Settings.Subtitle.OpenSubtitles -> {
                                    settingsView.displaySettings(Setting.OPEN_SUBTITLES)
                                }

                                Settings.Subtitle.SubDLSubtitles -> {
                                    settingsView.displaySettings(Setting.SUBDL)
                                }
                            }
                        }

                        is Settings.Subtitle.Style -> {
                            when (item) {
                                Settings.Subtitle.Style.ResetStyle -> {
                                    settingsView.onTextSizeSelected.invoke(Settings.Subtitle.Style.TextSize.DEFAULT)
                                    settingsView.onCaptionStyleChanged.invoke(Settings.Subtitle.Style.DEFAULT)
                                    settingsView.hide()
                                }
                                Settings.Subtitle.Style.FontColor -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_FONT_COLOR)
                                }
                                Settings.Subtitle.Style.TextSize -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_TEXT_SIZE)
                                }
                                Settings.Subtitle.Style.FontOpacity -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_FONT_OPACITY)
                                }
                                Settings.Subtitle.Style.EdgeStyle -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_EDGE_STYLE)
                                }
                                Settings.Subtitle.Style.BackgroundColor -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_BACKGROUND_COLOR)
                                }
                                Settings.Subtitle.Style.BackgroundOpacity -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_BACKGROUND_OPACITY)
                                }
                                Settings.Subtitle.Style.WindowColor -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_WINDOW_COLOR)
                                }
                                Settings.Subtitle.Style.WindowOpacity -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_WINDOW_OPACITY)
                                }
                                Settings.Subtitle.Style.Margin -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_MARGIN)
                                }
                            }
                        }

                        is Settings.Subtitle.Style.FontColor -> {
                            settingsView.onFontColorSelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.Style.TextSize -> {
                            settingsView.onTextSizeSelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.Style.FontOpacity -> {
                            settingsView.onFontOpacitySelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.Style.EdgeStyle -> {
                            settingsView.onEdgeStyleSelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.Style.BackgroundColor -> {
                            settingsView.onBackgroundColorSelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.Style.BackgroundOpacity -> {
                            settingsView.onBackgroundOpacitySelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.Style.WindowColor -> {
                            settingsView.onWindowColorSelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.Style.WindowOpacity -> {
                            settingsView.onWindowOpacitySelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.Style.Margin -> {
                            settingsView.onMarginSelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.OpenSubtitles.Subtitle -> {
                            settingsView.onOpenSubtitleSelected?.invoke(item)
                            settingsView.hide()
                        }

                        is Settings.Subtitle.SubDLSubtitles.Subtitle -> {
                            settingsView.onSubDLSubtitleSelected?.invoke(item)
                            settingsView.hide()
                        }

                        is Settings.Speed -> {
                            settingsView.onSpeedSelected.invoke(item)
                            settingsView.hide()
                        }



                        is Settings.ExtraBuffering -> {
                            settingsView.onExtraBufferingSelected.invoke(item)
                            settingsView.hide()
                        }

                        is Settings.SoftwareDecoder -> {
                            settingsView.onSoftwareDecoderSelected.invoke(item)
                            settingsView.hide()
                        }

                        is Settings.ChannelVariant -> {
                            Settings.ChannelVariant.list.forEach { it.isSelected = false }
                            item.isSelected = true
                            settingsView.onChannelVariantSelected?.invoke(item)
                            settingsView.hide()
                        }

                        is Settings.Server -> {
                            // Update selection immediately so the UI reflects
                            // the new server before the video finishes loading
                            Settings.Server.list.forEach { it.isSelected = false }
                            item.isSelected = true
                            settingsView.onServerSelected?.invoke(item)
                            settingsView.hide()
                        }
                        else -> {}
                    }
                }
            }

            binding.ivSettingIcon.apply {
                when (item) {
                    is Settings -> {
                        when (item) {
                            Settings.Quality -> setImageDrawable(
                                ContextCompat.getDrawable(context, R.drawable.ic_player_settings_quality)
                            )
                            Settings.Audio -> setImageDrawable(
                                ContextCompat.getDrawable(context, R.drawable.ic_player_settings_audio)
                            )
                            Settings.Subtitle -> setImageDrawable(
                                ContextCompat.getDrawable(
                                    context,
                                    when (Settings.Subtitle.selected) {
                                        is Settings.Subtitle.TextTrackInformation -> R.drawable.ic_player_settings_subtitle_on
                                        else -> R.drawable.ic_player_settings_subtitle_off
                                    }
                                )
                            )
                            Settings.Speed -> setImageDrawable(
                                ContextCompat.getDrawable(
                                    context,
                                    R.drawable.ic_player_settings_playback_speed
                                )
                                )

                            Settings.ChannelVariant -> setImageDrawable(
                                ContextCompat.getDrawable(
                                    context,
                                    R.drawable.ic_player_settings_servers
                                )
                            )

                            Settings.ExtraBuffering -> setImageDrawable(
                                ContextCompat.getDrawable(
                                    context,
                                    R.drawable.ic_player_settings_extra_buffer
                                )
                            )

                            Settings.SoftwareDecoder -> setImageDrawable(
                                ContextCompat.getDrawable(
                                    context,
                                    R.drawable.ic_player_settings_extra_buffer
                                )
                            )

                            Settings.Server -> setImageDrawable(
                                ContextCompat.getDrawable(
                                    context,
                                    R.drawable.ic_player_settings_servers
                                )
                            )
                            Settings.ManualZoom -> setImageDrawable(
                                ContextCompat.getDrawable(
                                    context,
                                    R.drawable.exo_styled_controls_aspect_ratio
                                )
                            )
                            else -> {}
                        }
                        visibility = View.VISIBLE
                    }

                    else -> {
                        visibility = View.GONE
                    }
                }
            }

            binding.vSettingColor.apply {
                when (item) {
                    is Settings.Subtitle.Style.FontColor -> {
                        backgroundTintList = ColorStateList.valueOf(item.color)
                        visibility = View.VISIBLE
                    }

                    is Settings.Subtitle.Style.BackgroundColor -> {
                        backgroundTintList = ColorStateList.valueOf(item.color)
                        visibility = View.VISIBLE
                    }

                    is Settings.Subtitle.Style.WindowColor -> {
                        backgroundTintList = ColorStateList.valueOf(item.color)
                        visibility = View.VISIBLE
                    }

                    else -> {
                        visibility = View.GONE
                    }
                }
            }

            binding.tvSettingMainText.apply {
                text = when (item) {
                    is Settings -> when (item) {
                        Settings.Quality -> context.getString(R.string.player_settings_quality_label)
                        Settings.Audio -> context.getString(R.string.player_settings_audio_label)
                        Settings.Subtitle -> context.getString(R.string.player_settings_subtitles_label)
                        Settings.Speed -> context.getString(R.string.player_settings_speed_label)
                        Settings.ChannelVariant -> context.getString(R.string.player_settings_channel_variant_label)
                        Settings.ExtraBuffering -> context.getString(R.string.player_settings_extra_buffer_server_label)
                        Settings.SoftwareDecoder -> context.getString(R.string.player_settings_software_decoder_label)
                        Settings.Server -> context.getString(R.string.player_settings_servers_label)
                        Settings.ManualZoom -> context.getString(R.string.player_settings_manual_zoom_label)
                        else -> ""
                    }

                    is Settings.Audio -> when (item) {
                        is Settings.Audio.AudioTrackInformation -> item.name
                    }

                    is Settings.Quality -> when (item) {
                        is Settings.Quality.Auto -> when {
                            item.isSelected -> when (val track = item.currentTrack) {
                                null -> context.getString(R.string.player_settings_quality_auto)
                                else -> context.getString(
                                    R.string.player_settings_quality_auto_selected,
                                    track.height
                                )
                            }

                            else -> context.getString(R.string.player_settings_quality_auto)
                        }
                        is Settings.Quality.VideoTrackInformation -> context.getString(
                            R.string.player_settings_quality,
                            item.height
                        )
                    }

                    is Settings.Subtitle -> when (item) {
                        Settings.Subtitle.Style -> context.getString(R.string.player_settings_caption_style_label)
                        is Settings.Subtitle.None -> context.getString(R.string.player_settings_subtitles_off)
                        is Settings.Subtitle.TextTrackInformation -> item.label.ifEmpty { item.name }
                        Settings.Subtitle.LocalSubtitles -> context.getString(R.string.player_settings_local_subtitles_label)
                        Settings.Subtitle.OpenSubtitles -> context.getString(R.string.player_settings_open_subtitles_label)
                        Settings.Subtitle.SubDLSubtitles -> context.getString(R.string.player_settings_subdl_label)
                    }

                    is Settings.Subtitle.Style -> when (item) {
                        Settings.Subtitle.Style.ResetStyle -> context.getString(R.string.player_settings_caption_style_reset_style_label)
                        Settings.Subtitle.Style.FontColor -> context.getString(R.string.player_settings_caption_style_font_color_label)
                        Settings.Subtitle.Style.TextSize -> context.getString(R.string.player_settings_caption_style_text_size_label)
                        Settings.Subtitle.Style.FontOpacity -> context.getString(R.string.player_settings_caption_style_font_opacity_label)
                        Settings.Subtitle.Style.EdgeStyle -> context.getString(R.string.player_settings_caption_style_edge_style_label)
                        Settings.Subtitle.Style.BackgroundColor -> context.getString(R.string.player_settings_caption_style_background_color_label)
                        Settings.Subtitle.Style.BackgroundOpacity -> context.getString(R.string.player_settings_caption_style_background_opacity_label)
                        Settings.Subtitle.Style.WindowColor -> context.getString(R.string.player_settings_caption_style_window_color_label)
                        Settings.Subtitle.Style.WindowOpacity -> context.getString(R.string.player_settings_caption_style_window_opacity_label)
                        Settings.Subtitle.Style.Margin -> context.getString(R.string.player_settings_caption_style_margin_label)
                    }

                    is Settings.Subtitle.Style.FontColor -> context.getString(item.stringId)
                    is Settings.Subtitle.Style.Margin -> item.value.toString()

                    is Settings.Subtitle.Style.TextSize -> context.getString(item.stringId)

                    is Settings.Subtitle.Style.FontOpacity -> context.getString(item.stringId)

                    is Settings.Subtitle.Style.EdgeStyle -> context.getString(item.stringId)

                    is Settings.Subtitle.Style.BackgroundColor -> context.getString(item.stringId)

                    is Settings.Subtitle.Style.BackgroundOpacity -> context.getString(item.stringId)

                    is Settings.Subtitle.Style.WindowColor -> context.getString(item.stringId)

                    is Settings.Subtitle.Style.WindowOpacity -> context.getString(item.stringId)

                    is Settings.Subtitle.OpenSubtitles.Subtitle -> item.openSubtitle.subFileName

                    is Settings.Subtitle.SubDLSubtitles.Subtitle -> item.subDLSubtitle.releaseName ?: item.subDLSubtitle.name

                    is Settings.Speed -> context.getString(item.stringId)

                    is Settings.ExtraBuffering -> context.getString(item.stringId)

                    is Settings.SoftwareDecoder -> context.getString(item.stringId)

                    is Settings.ChannelVariant -> {
                        val prefix = when {
                            item.isSelected && item.isIptv -> "▶ "
                            item.isFavorite && item.isIptv -> "★ "
                            else -> ""
                        }
                        prefix + item.name
                    }
                    is Settings.Server -> item.name

                    else -> ""
                }

                // Green text for the active IPTV source, default for others.
                // For the default case we MUST use the ColorStateList (not a
                // single int) so the focused state can switch the text to
                // black on the white focus background — otherwise the text
                // is invisible when the row is focused.
                if (item is Settings.ChannelVariant && item.isIptv && item.isSelected) {
                    setTextColor(0xFF4CAF50.toInt())  // Material Green
                } else {
                    setTextColor(ContextCompat.getColorStateList(context, R.color.setting_text))
                }
            }

            binding.tvSettingSubText.apply {
                text = when (item) {
                    is Settings -> when (item) {
                        Settings.Quality -> when (val selected = Settings.Quality.selected) {
                            is Settings.Quality.Auto -> when (val track = selected.currentTrack) {
                                null -> context.getString(R.string.player_settings_quality_auto)
                                else -> context.getString(
                                    R.string.player_settings_quality_auto_selected,
                                    track.height
                                )
                            }
                            is Settings.Quality.VideoTrackInformation -> context.getString(
                                R.string.player_settings_quality,
                                selected.height
                            )
                        }
                        Settings.Audio -> Settings.Audio.selected?.name
                        Settings.Subtitle -> when (val selected = Settings.Subtitle.selected) {
                            is Settings.Subtitle.TextTrackInformation -> selected.label
                            else -> context.getString(R.string.player_settings_subtitles_off)
                        }
                        Settings.Speed -> context.getString(Settings.Speed.selected.stringId)
                        Settings.ExtraBuffering -> context.getString(Settings.ExtraBuffering.selected.stringId)
                        Settings.ChannelVariant -> Settings.ChannelVariant.selected?.name ?: ""
                        Settings.Server -> Settings.Server.selected?.name ?: ""
                        Settings.ManualZoom -> ""
                        else -> ""
                    }

                    is Settings.Subtitle -> when (item) {
                        Settings.Subtitle.Style -> context.getString(R.string.player_settings_caption_style_sub_label)
                        is Settings.Subtitle.TextTrackInformation -> item.language ?: ""
                        else -> ""
                    }

                    is Settings.Subtitle.Style -> when (item) {
                        Settings.Subtitle.Style.ResetStyle -> ""
                        Settings.Subtitle.Style.FontColor -> context.getString(Settings.Subtitle.Style.FontColor.selected.stringId)
                        Settings.Subtitle.Style.TextSize -> context.getString(Settings.Subtitle.Style.TextSize.selected.stringId)
                        Settings.Subtitle.Style.FontOpacity -> context.getString(Settings.Subtitle.Style.FontOpacity.selected.stringId)
                        Settings.Subtitle.Style.EdgeStyle -> context.getString(Settings.Subtitle.Style.EdgeStyle.selected.stringId)
                        Settings.Subtitle.Style.BackgroundColor -> context.getString(Settings.Subtitle.Style.BackgroundColor.selected.stringId)
                        Settings.Subtitle.Style.BackgroundOpacity -> context.getString(Settings.Subtitle.Style.BackgroundOpacity.selected.stringId)
                        Settings.Subtitle.Style.WindowColor -> context.getString(Settings.Subtitle.Style.WindowColor.selected.stringId)
                        Settings.Subtitle.Style.WindowOpacity -> context.getString(Settings.Subtitle.Style.WindowOpacity.selected.stringId)
                        Settings.Subtitle.Style.Margin -> Settings.Subtitle.Style.Margin.selected.value.toString()
                    }

                    is Settings.Subtitle.OpenSubtitles.Subtitle -> item.openSubtitle.languageName

                    is Settings.Subtitle.SubDLSubtitles.Subtitle -> item.subDLSubtitle.lang?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() } ?: ""

                    else -> ""
                }
                visibility = when {
                    text.isEmpty() -> View.GONE
                    else -> View.VISIBLE
                }
            }

            binding.ivSettingIsSelected.apply {
                visibility = when (item) {
                    is Settings.Quality -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Audio -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle -> when (item) {
                        is Settings.Subtitle.None -> when {
                            item.isSelected -> View.VISIBLE
                            else -> View.GONE
                        }
                        is Settings.Subtitle.TextTrackInformation -> when {
                            item.isSelected -> View.VISIBLE
                            else -> View.GONE
                        }
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.FontColor -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.TextSize -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.FontOpacity -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.EdgeStyle -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.BackgroundColor -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.BackgroundOpacity -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.WindowColor -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.WindowOpacity -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.Margin -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Speed -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.ExtraBuffering -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.SoftwareDecoder -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Server -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.ChannelVariant -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    else -> View.GONE
                }
            }

            // 2026-05-08 : ban croix sur picker Server IPTV (TV).
            // Click croix → toggle ban (persisté SharedPrefs)
            // Si banni : alpha 0.4 + ne sera pas joué auto au démarrage chaîne.
            // Cumul illimité (pas de max).
            val srvChannelKey = (item as? Settings.Server)?.channelKey
            if (item is Settings.Server && item.isIptv && srvChannelKey != null) {
                // Croix (✕) — toggle ban persistant
                val isBanned = IptvBannedServers.isBanned(srvChannelKey, item.id)
                item.isBanned = isBanned
                binding.root.alpha = if (isBanned) 0.4f else 1.0f
                binding.ivSettingBan.visibility = View.VISIBLE
                binding.ivSettingBan.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (isBanned) 0xFFFF4444.toInt() else 0xFF808080.toInt()
                )
                binding.ivSettingBan.setOnClickListener {
                    if (isBanned) {
                        IptvBannedServers.unban(srvChannelKey, item.id)
                    } else {
                        IptvBannedServers.recordBan(srvChannelKey, item.id)
                    }
                    settingsView.refreshServerList()
                    // 2026-05-08 : trigger refetch pour backfill compensation
                    settingsView.onServerBanned?.invoke(item)
                }

                // Cœur (♥) — toggle favori (max 5/chaîne)
                val isFav = IptvFavorites.isFavorite(srvChannelKey, item.id)
                binding.ivSettingFavorite.visibility = View.VISIBLE
                binding.ivSettingFavorite.setImageResource(
                    if (isFav) R.drawable.ic_favorite_enable
                    else R.drawable.ic_favorite_disable
                )
                binding.ivSettingFavorite.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (isFav) 0xFFFF4444.toInt() else 0xFF808080.toInt()
                )
                binding.ivSettingFavorite.setOnClickListener {
                    IptvFavorites.toggleFavorite(srvChannelKey, item.id)
                    settingsView.refreshServerList()
                    settingsView.onServerFavoriteToggled?.invoke(item)
                }

                // D-pad : right row → favorite → ban
                binding.root.nextFocusRightId = R.id.iv_setting_favorite
                binding.ivSettingFavorite.nextFocusRightId = R.id.iv_setting_ban
                binding.ivSettingBan.nextFocusRightId = View.NO_ID
            } else if (item is Settings.Server) {
                binding.root.alpha = 1.0f
            }

            // IPTV-specific buttons: favorite (★) and ban (✕) — for ChannelVariant items
            if (item is Settings.ChannelVariant && item.isIptv) {
                // Favorite button
                binding.ivSettingFavorite.visibility = View.VISIBLE
                binding.ivSettingFavorite.setImageResource(
                    if (item.isFavorite) R.drawable.ic_favorite_enable
                    else R.drawable.ic_favorite_disable
                )
                binding.ivSettingFavorite.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (item.isFavorite) 0xFFFF4444.toInt() else 0xFF808080.toInt()
                )
                binding.ivSettingFavorite.setOnClickListener {
                    if (item.channelKey.isBlank()) {
                        android.util.Log.w("FavoriteDebug",
                            "channelKey is BLANK on TV — toggleFavorite will no-op!")
                    }
                    val nowFav = IptvFavorites.toggleFavorite(item.channelKey, item.id)
                    item.isFavorite = nowFav
                    // 2026-05-08 : N favoris autorisés (max 20 store-side, pas 1 forcé).
                    settingsView.onChannelVariantFavoriteToggled?.invoke(item)
                    settingsView.refreshChannelVariantList()
                }

                // Ban button (✕) — toggle grise/dégrise (persisté SharedPrefs)
                val variantBanned = IptvBannedServers.isBanned(item.channelKey, item.id)
                binding.root.alpha = if (variantBanned) 0.4f else 1.0f
                binding.ivSettingBan.visibility = View.VISIBLE
                binding.ivSettingBan.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (variantBanned) 0xFFFF4444.toInt() else 0xFF808080.toInt()
                )
                binding.ivSettingBan.setOnClickListener {
                    if (variantBanned) {
                        IptvBannedServers.unban(item.channelKey, item.id)
                    } else {
                        IptvBannedServers.recordBan(item.channelKey, item.id)
                    }
                    settingsView.onChannelVariantBanned?.invoke(item)
                    settingsView.refreshChannelVariantList()
                }

                // D-pad: right goes to favorite, then ban
                binding.root.nextFocusRightId = R.id.iv_setting_favorite
                binding.ivSettingFavorite.nextFocusRightId = R.id.iv_setting_ban
                binding.ivSettingBan.nextFocusRightId = View.NO_ID
            } else {
                // 2026-05-08 / 2026-05-09 : NE PAS reset ivSettingFavorite/ivSettingBan
                // à GONE si c'est un Settings.Server IPTV — la branche plus haut
                // (ligne ~817) les a déjà mis VISIBLE pour le picker IPTV.
                // Sans ce check, le ❤ et la ✕ étaient invisibles pour les servers IPTV
                // sur TV (bug : "le favori serveur n'apparaît pas sur Vegeta TV").
                if (!(item is Settings.Server && item.isIptv && item.channelKey != null)) {
                    binding.ivSettingFavorite.visibility = View.GONE
                    binding.ivSettingBan.visibility = View.GONE
                }
            }

            // Downloads disabled on TV — not enough storage on these devices
            // 2026-05-09 : NE PAS reset nextFocusRightId pour les Settings.Server
            // IPTV — la branche ligne ~854 a déjà mis le focus chain
            // root → favorite → ban. Le reset à NO_ID rendait le ❤/✕
            // inaccessibles au D-pad sur Vegeta TV.
            binding.ivSettingDownload.apply {
                visibility = View.GONE
                val isIptvServerWithKey = item is Settings.Server && item.isIptv && item.channelKey != null
                val isIptvVariant = item is Settings.ChannelVariant && item.isIptv
                if (!isIptvServerWithKey && !isIptvVariant) {
                    binding.root.nextFocusRightId = View.NO_ID
                }
            }

            binding.ivSettingEnter.apply {
                visibility = when (item) {
                    is Settings -> {
                        when(item) {
                            Settings.Quality,
                            Settings.Audio,
                            Settings.Subtitle,
                            Settings.Speed,
                            Settings.ChannelVariant,
                            Settings.ExtraBuffering,
                            Settings.SoftwareDecoder,
                            Settings.Server -> View.VISIBLE
                            else -> View.GONE
                        }
                    }

                    is Settings.Subtitle -> when (item) {
                        Settings.Subtitle.Style -> View.VISIBLE
                        is Settings.Subtitle.None -> View.GONE
                        is Settings.Subtitle.TextTrackInformation -> View.GONE
                        Settings.Subtitle.LocalSubtitles -> View.VISIBLE
                        Settings.Subtitle.OpenSubtitles -> View.VISIBLE
                        Settings.Subtitle.SubDLSubtitles -> View.VISIBLE
                    }

                    is Settings.Subtitle.Style -> when (item) {
                        Settings.Subtitle.Style.ResetStyle -> View.GONE
                        else -> View.VISIBLE
                    }

                    else -> View.GONE
                }
            }
        }
    }
}
