package com.example.mediafinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

data class MediaItem(
    val id: String,
    val title: String,
    val previewUrl: String,
    val source: String,
    val isVideo: Boolean
)

interface PexelsApi {
    @GET("v1/search")
    suspend fun searchPhotos(
        @Header("Authorization") apiKey: String,
        @Query("query") query: String,
        @Query("per_page") perPage: Int = 30
    ): PexelsPhotoResponse

    @GET("videos/search")
    suspend fun searchVideos(
        @Header("Authorization") apiKey: String,
        @Query("query") query: String,
        @Query("per_page") perPage: Int = 30
    ): PexelsVideoResponse
}

data class PexelsPhotoResponse(val photos: List<PexelsPhoto> = emptyList())
data class PexelsPhoto(val id: Long, val url: String, val alt: String?, val src: PexelsSrc)
data class PexelsSrc(val medium: String, val large: String)
data class PexelsVideoResponse(val videos: List<PexelsVideo> = emptyList())
data class PexelsVideo(val id: Long, val url: String, val image: String, val user: PexelsUser?)
data class PexelsUser(val name: String?)

interface PixabayApi {
    @GET("api/")
    suspend fun searchImages(
        @Query("key") key: String,
        @Query("q") query: String,
        @Query("per_page") perPage: Int = 30
    ): PixabayImageResponse

    @GET("api/videos/")
    suspend fun searchVideos(
        @Query("key") key: String,
        @Query("q") query: String,
        @Query("per_page") perPage: Int = 30
    ): PixabayVideoResponse
}

data class PixabayImageResponse(val hits: List<PixabayImage> = emptyList())
data class PixabayImage(val id: Long, val pageURL: String, val previewURL: String, val largeImageURL: String)
data class PixabayVideoResponse(val hits: List<PixabayVideo> = emptyList())
data class PixabayVideo(val id: Long, val pageURL: String, val videos: PixabayVideoFiles)
data class PixabayVideoFiles(val medium: PixabayVideoFile?, val small: PixabayVideoFile?)
data class PixabayVideoFile(val url: String?)

class MediaRepository {
    private val pexels = Retrofit.Builder()
        .baseUrl("https://api.pexels.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build().create(PexelsApi::class.java)

    private val pixabay = Retrofit.Builder()
        .baseUrl("https://pixabay.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build().create(PixabayApi::class.java)

    suspend fun search(query: String, mode: String): List<MediaItem> = coroutineScope {
        val tasks = mutableListOf<kotlinx.coroutines.Deferred<List<MediaItem>>>()

        if (BuildConfig.PEXELS_API_KEY.isNotBlank()) {
            if (mode != "video") tasks += async {
                runCatching {
                    pexels.searchPhotos(BuildConfig.PEXELS_API_KEY, query).photos.map {
                        MediaItem(it.id.toString(), it.alt ?: "Pexels photo", it.src.medium, "Pexels", false)
                    }
                }.getOrDefault(emptyList())
            }
            if (mode != "photo") tasks += async {
                runCatching {
                    pexels.searchVideos(BuildConfig.PEXELS_API_KEY, query).videos.map {
                        MediaItem(it.id.toString(), it.user?.name ?: "Pexels video", it.image, "Pexels", true)
                    }
                }.getOrDefault(emptyList())
            }
        }

        if (BuildConfig.PIXABAY_API_KEY.isNotBlank()) {
            if (mode != "video") tasks += async {
                runCatching {
                    pixabay.searchImages(BuildConfig.PIXABAY_API_KEY, query).hits.map {
                        MediaItem(it.id.toString(), "Pixabay image", it.previewURL, "Pixabay", false)
                    }
                }.getOrDefault(emptyList())
            }
            if (mode != "photo") tasks += async {
                runCatching {
                    pixabay.searchVideos(BuildConfig.PIXABAY_API_KEY, query).hits.mapNotNull {
                        val url = it.videos.medium?.url ?: it.videos.small?.url
                        url?.let { u -> MediaItem(it.id.toString(), "Pixabay video", u, "Pixabay", true) }
                    }
                }.getOrDefault(emptyList())
            }
        }

        tasks.awaitAll().flatten().distinctBy { "${it.source}:${it.id}:${it.isVideo}" }
    }
}

class MainViewModel : ViewModel() {
    private val repository = MediaRepository()
    var query by mutableStateOf("")
    var mode by mutableStateOf("all")
    var loading by mutableStateOf(false)
    var results by mutableStateOf<List<MediaItem>>(emptyList())
    var error by mutableStateOf<String?>(null)

    fun search() {
        if (query.isBlank()) return
        viewModelScope.launch {
            loading = true
            error = null
            results = repository.search(query.trim(), mode)
            if (results.isEmpty() && BuildConfig.PEXELS_API_KEY.isBlank() && BuildConfig.PIXABAY_API_KEY.isBlank()) {
                error = "Добавь API-ключи Pexels и Pixabay в app/build.gradle.kts"
            }
            loading = false
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MediaFinderApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaFinderApp(vm: MainViewModel = viewModel()) {
    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Media Finder") }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Что ищем?") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(vm.mode == "all", { vm.mode = "all" }, "Все")
                    FilterChip(vm.mode == "photo", { vm.mode = "photo" }, "Фото")
                    FilterChip(vm.mode == "video", { vm.mode = "video" }, "Видео")
                    Button(onClick = vm::search) { Text("Искать") }
                }
                Spacer(Modifier.height(12.dp))
                if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(vm.results) { item ->
                        Card {
                            Column {
                                AsyncImage(
                                    model = item.previewUrl,
                                    contentDescription = item.title,
                                    modifier = Modifier.fillMaxWidth().height(180.dp),
                                    contentScale = ContentScale.Crop
                                )
                                Text(
                                    "${item.source} • ${if (item.isVideo) "Видео" else "Фото"}",
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FilterChip(selected: Boolean, onClick: () -> Unit, text: String) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(text) })
}
