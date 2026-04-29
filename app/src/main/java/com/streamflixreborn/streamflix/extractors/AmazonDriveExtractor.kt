package com.streamflixreborn.streamflix.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

class AmazonDriveExtractor : Extractor() {

    override val name = "AmazonDrive"
    override val mainUrl = "https://www.amazon.com"

    override suspend fun extract(link: String): Video {
        val shareIdMatch = Regex("""/shares/([^/]+)""").find(link)
        val shareId = shareIdMatch?.groupValues?.get(1)
            ?: throw Exception("ShareId not found in URL")

        val service = Extractor.createGsonService<Service>(mainUrl)

        val shareResponse = service.getShare(
            shareId = shareId,
            resourceVersion = "V2",
            contentType = "JSON",
            asset = "ALL"
        )
        val shareJson = JsonParser.parseString(shareResponse.string()).asJsonObject
        val nodeId = shareJson.getAsJsonObject("nodeInfo")?.get("id")?.asString
            ?: throw Exception("Node ID not found in share response")

        val childrenResponse = service.getChildren(
            nodeId = nodeId,
            resourceVersion = "V2",
            contentType = "JSON",
            limit = "200",
            sort = """["kind DESC", "modifiedDate DESC"]""",
            asset = "ALL",
            tempLink = "true",
            shareId = shareId
        )
        val childrenJson = JsonParser.parseString(childrenResponse.string()).asJsonObject

        val tempLink = childrenJson.getAsJsonArray("data")
            ?.get(0)?.asJsonObject
            ?.get("tempLink")?.asString
            ?: throw Exception("No tempLink found in data[0]")

        return Video(
            source = tempLink
        )
    }

    private interface Service {
        @GET("drive/v1/shares/{shareId}")
        suspend fun getShare(
            @Path("shareId") shareId: String,
            @Query("resourceVersion") resourceVersion: String,
            @Query("ContentType") contentType: String,
            @Query("asset") asset: String
        ): ResponseBody

        @GET("drive/v1/nodes/{nodeId}/children")
        suspend fun getChildren(
            @Path("nodeId") nodeId: String,
            @Query("resourceVersion") resourceVersion: String,
            @Query("ContentType") contentType: String,
            @Query("limit") limit: String,
            @Query("sort") sort: String,
            @Query("asset") asset: String,
            @Query("tempLink") tempLink: String,
            @Query("shareId") shareId: String
        ): ResponseBody
    }
}

