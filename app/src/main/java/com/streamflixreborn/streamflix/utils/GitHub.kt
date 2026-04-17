package com.streamflixreborn.streamflix.utils

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

object GitHub {

        private const val TOKEN = "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"

        private val service = ApiService.build()


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
            }


                private interface ApiService {

                            companion object {
                                            fun build(): ApiService {
                                                                val client = OkHttpClient.Builder()
                                                                                    .addInterceptor { chain ->
                                                                                                                val request = chain.request().newBuilder()
                                                                                                                                            .addHeader("Authorization", "Bearer $TOKEN")
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
                    