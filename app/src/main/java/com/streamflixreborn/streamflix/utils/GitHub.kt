package com.streamflixreborn.streamflix.utils

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

object GitHub {

    // 2026-05-03 : remplacement du PAT admin (qui pouvait push/admin sur le repo
    // = risque distribution de malware via update auto si extrait de l'APK) par
    // un PAT fine-grained read-only (Contents:Read + Metadata:Read uniquement,
    // scope = mobile-client-v2 seulement). Si extrait, l'attaquant peut juste
    // lire le repo, aucun écriture/admin possible.
    // Ancien token à révoquer côté GitHub après que les users aient updaté.
    val token: String by lazy {
        val parts = arrayOf(
            "github_pat_11BMK",
            "YM6Q0q1tpN5tn7m1G",
            "_QD0NmpXJCOaMhffZ",
            "rgRi7TeuggjQtGQ25",
            "TQq3EMqgvhB42MDC",
            "IClSQgtdVn"
        )
        parts.joinToString("")
    }

    private val service = ApiService.build()

    /**
     * 2026-05-03: service "public" sans Authorization header. Sert au fallback
     * dans InAppUpdater quand le repo principal (Xx-nanico-xX) n'est pas
     * joignable et qu'on tape un repo backup PUBLIC (ex: Logami61). Le token
     * read-only de l'app a un scope verrouillé sur mobile-client-v2 et renvoie
     * 401 sur les autres repos — sans auth on accède à tout repo public.
     */
    private val publicService = ApiService.buildPublic()

    object Releases {

        suspend fun getReleases(
            owner: String,
            repo: String,
        ): List<Release> {
            return service.getReleases(
                owner = owner,
                repo = repo,
            )
        }

        suspend fun getLatestRelease(
            owner: String,
            repo: String,
        ): Release {
            return service.getLatestRelease(
                owner = owner,
                repo = repo,
            )
        }

        /** Variante sans auth pour repos publics (utilisée par le fallback). */
        suspend fun getLatestReleasePublic(
            owner: String,
            repo: String,
        ): Release {
            return publicService.getLatestRelease(
                owner = owner,
                repo = repo,
            )
        }

        /** Variante sans auth pour repos publics (utilisée par le fallback). */
        suspend fun getReleasesPublic(
            owner: String,
            repo: String,
        ): List<Release> {
            return publicService.getReleases(
                owner = owner,
                repo = repo,
            )
        }
    }

    private interface ApiService {

        companion object {
            fun build(): ApiService {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .addHeader("Authorization", "Bearer $token")
                            .addHeader("Accept", "application/vnd.github+json")
                            .build()
                        chain.proceed(request)
                    }
                    .build()

                val retrofit = Retrofit.Builder()
                    .baseUrl("https://api.github.com/")
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                return retrofit.create(ApiService::class.java)
            }

            /**
             * Variante SANS Authorization header — utilisée par le fallback de
             * InAppUpdater pour interroger un repo backup public (Logami61) si
             * le repo principal (Xx-nanico-xX) est indisponible. Le token de
             * l'app a un scope verrouillé sur Xx-nanico-xX/mobile-client-v2 et
             * renverrait 401 sur tout autre repo, même public.
             */
            fun buildPublic(): ApiService {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .addHeader("Accept", "application/vnd.github+json")
                            .build()
                        chain.proceed(request)
                    }
                    .build()

                val retrofit = Retrofit.Builder()
                    .baseUrl("https://api.github.com/")
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                return retrofit.create(ApiService::class.java)
            }
        }

        @GET("repos/{owner}/{repo}/releases")
        suspend fun getReleases(
            @Path("owner") owner: String,
            @Path("repo") repo: String,
        ): List<Release>

        @GET("repos/{owner}/{repo}/releases/latest")
        suspend fun getLatestRelease(
            @Path("owner") owner: String,
            @Path("repo") repo: String,
        ): Release
    }

    data class Release(
        @SerializedName("url") val url: String,
        @SerializedName("html_url") val htmlUrl: String,
        @SerializedName("assets_url") val assetsUrl: String,
        @SerializedName("upload_url") val uploadUrl: String,
        @SerializedName("tarball_url") val tarballUrl: String?,
        @SerializedName("zipball_url") val zipballUrl: String?,
        @SerializedName("id") val id: Int,
        @SerializedName("node_id") val nodeId: String,
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("target_commitish") val targetCommitish: String,
        @SerializedName("name") val name: String?,
        @SerializedName("body") val body: String? = null,
        @SerializedName("draft") val draft: Boolean,
        @SerializedName("prerelease") val prerelease: Boolean,
        @SerializedName("created_at") val createdAt: String,
        @SerializedName("published_at") val publishedAt: String?,
        @SerializedName("author") val author: User,
        @SerializedName("assets") val assets: List<Asset>,
        @SerializedName("body_html") val bodyHtml: String = "",
        @SerializedName("body_text") val bodyText: String = "",
        @SerializedName("mentions_count") val mentionsCount: Int = 0,
        @SerializedName("discussion_url") val discussionUrl: String = "",
        @SerializedName("reactions") val reactions: Reactions? = null,
    ) {

        data class Asset(
            @SerializedName("url") val url: String,
            @SerializedName("browser_download_url") val browserDownloadUrl: String,
            @SerializedName("id") val id: Int,
            @SerializedName("node_id") val nodeId: String,
            @SerializedName("name") val name: String,
            @SerializedName("label") val label: String?,
            @SerializedName("state") val state: String,
            @SerializedName("content_type") val contentType: String,
            @SerializedName("size") val size: Int,
            @SerializedName("download_count") val downloadCount: Int,
            @SerializedName("created_at") val createdAt: String,
            @SerializedName("updated_at") val updatedAt: String,
            @SerializedName("uploader") val uploader: User,
        )
    }

    data class User(
        @SerializedName("name") val name: String? = "",
        @SerializedName("email") val email: String? = "",
        @SerializedName("login") val login: String,
        @SerializedName("id") val id: Int,
        @SerializedName("node_id") val nodeId: String,
        @SerializedName("avatar_url") val avatarUrl: String,
        @SerializedName("gravatar_id") val gravatarId: String?,
        @SerializedName("url") val url: String,
        @SerializedName("html_url") val htmlUrl: String,
        @SerializedName("followers_url") val followersUrl: String,
        @SerializedName("following_url") val followingUrl: String,
        @SerializedName("gists_url") val gistsUrl: String,
        @SerializedName("starred_url") val starredUrl: String,
        @SerializedName("subscriptions_url") val subscriptionsUrl: String,
        @SerializedName("organizations_url") val organizationsUrl: String,
        @SerializedName("repos_url") val reposUrl: String,
        @SerializedName("events_url") val eventsUrl: String,
        @SerializedName("received_events_url") val receivedEventsUrl: String,
        @SerializedName("type") val type: String,
        @SerializedName("site_admin") val siteAdmin: Boolean,
        @SerializedName("starred_at") val starredAt: String = "",
    )

    data class Reactions(
        @SerializedName("url") val url: String,
        @SerializedName("total_count") val totalCount: Int,
        @SerializedName("+1") val plus1: Int,
        @SerializedName("-1") val minus1: Int,
        @SerializedName("laugh") val laugh: Int,
        @SerializedName("confused") val confused: Int,
        @SerializedName("heart") val heart: Int,
        @SerializedName("hooray") val hooray: Int,
        @SerializedName("eyes") val eyes: Int,
        @SerializedName("rocket") val rocket: Int,
    )
}
