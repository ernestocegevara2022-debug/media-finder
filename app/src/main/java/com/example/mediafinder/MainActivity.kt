package com.example.mediafinder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    val sourceUrl: String,
    val source: String,
    val isVideo: Boolean
) {
    val key: String get() = "$source:$id:${if (isVideo) "v" else "p"}"
}

interface PexelsApi {
    @GET("v1/search")
    suspend fun searchPhotos(
        @Header("Authorization") apiKey: String,
        @Query("query") query: String,
        @Query("locale") locale: String = "ru-RU",
        @Query("per_page") perPage: Int = 30
    ): PexelsPhotoResponse

    @GET("v1/videos/search")
    suspend fun searchVideos(
        @Header("Authorization") apiKey: String,
        @Query("query") query: String,
        @Query("locale") locale: String = "ru-RU",
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
        @Query("lang") lang: String = "ru",
        @Query("per_page") perPage: Int = 30
    ): PixabayImageResponse

    @GET("api/videos/")
    suspend fun searchVideos(
        @Query("key") key: String,
        @Query("q") query: String,
        @Query("lang") lang: String = "ru",
        @Query("per_page") perPage: Int = 30
    ): PixabayVideoResponse
}

data class PixabayImageResponse(val hits: List<PixabayImage> = emptyList())
data class PixabayImage(val id: Long, val pageURL: String, val previewURL: String, val largeImageURL: String)
data class PixabayVideoResponse(val hits: List<PixabayVideo> = emptyList())
data class PixabayVideo(val id: Long, val pageURL: String, val videos: PixabayVideoFiles)
data class PixabayVideoFiles(val medium: PixabayVideoFile?, val small: PixabayVideoFile?)
data class PixabayVideoFile(val url: String?, val thumbnail: String? = null)

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
                        MediaItem(it.id.toString(), it.alt ?: "Фото Pexels", it.src.medium, it.url, "Pexels", false)
                    }
                }.getOrDefault(emptyList())
            }
            if (mode != "photo") tasks += async {
                runCatching {
                    pexels.searchVideos(BuildConfig.PEXELS_API_KEY, query).videos.map {
                        MediaItem(it.id.toString(), it.user?.name ?: "Видео Pexels", it.image, it.url, "Pexels", true)
                    }
                }.getOrDefault(emptyList())
            }
        }

        if (BuildConfig.PIXABAY_API_KEY.isNotBlank()) {
            if (mode != "video") tasks += async {
                runCatching {
                    pixabay.searchImages(BuildConfig.PIXABAY_API_KEY, query).hits.map {
                        MediaItem(it.id.toString(), "Фото Pixabay", it.previewURL, it.pageURL, "Pixabay", false)
                    }
                }.getOrDefault(emptyList())
            }
            if (mode != "photo") tasks += async {
                runCatching {
                    pixabay.searchVideos(BuildConfig.PIXABAY_API_KEY, query).hits.mapNotNull {
                        val file = it.videos.medium ?: it.videos.small
                        val url = file?.url
                        url?.let { _ -> MediaItem(it.id.toString(), "Видео Pixabay", file.thumbnail ?: "", it.pageURL, "Pixabay", true) }
                    }
                }.getOrDefault(emptyList())
            }
        }

        tasks.awaitAll().flatten().distinctBy { it.key }
    }
}

class MainViewModel : ViewModel() {
    private val repository = MediaRepository()
    private val prefs by lazy { AppPrefs.instance }

    var query by mutableStateOf("")
    var mode by mutableStateOf("all")
    var section by mutableStateOf("search")
    var loading by mutableStateOf(false)
    var results by mutableStateOf<List<MediaItem>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set

    init { favorites = prefs.loadFavorites() }

    fun search() {
        if (query.isBlank()) return
        viewModelScope.launch {
            loading = true
            error = null
            results = repository.search(query.trim(), mode)
            if (results.isEmpty() && BuildConfig.PEXELS_API_KEY.isBlank() && BuildConfig.PIXABAY_API_KEY.isBlank()) {
                error = "Нужны API-ключи Pexels и Pixabay. Добавим их на следующем шаге."
            } else if (results.isEmpty()) {
                error = "Ничего не найдено. Попробуй другой запрос."
            }
            loading = false
        }
    }

    fun toggleFavorite(item: MediaItem) {
        favorites = if (item.key in favorites) favorites - item.key else favorites + item.key
        prefs.saveFavorites(favorites)
    }
}

class AppPrefs private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("media_finder", Context.MODE_PRIVATE)

    fun loadFavorites(): Set<String> = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()

    fun saveFavorites(values: Set<String>) {
        prefs.edit().putStringSet("favorites", values).apply()
    }

    companion object {
        lateinit var instance: AppPrefs
            private set

        fun init(context: Context) { instance = AppPrefs(context.applicationContext) }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(applicationContext)
        setContent { MediaFinderApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaFinderApp(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val visibleItems = if (vm.section == "favorites") vm.results.filter { it.key in vm.favorites } else vm.results

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Media Finder") })
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(vm.section == "search", { vm.section = "search" }, icon = { Text("🔎") }, label = { Text("Поиск") })
                    NavigationBarItem(vm.section == "favorites", { vm.section = "favorites" }, icon = { Text("♥") }, label = { Text("Избранное") })
                    NavigationBarItem(false, { }, icon = { Text("★") }, label = { Text("Премиум") }, enabled = false)
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
                if (vm.section == "search") {
                    OutlinedTextField(
                        value = vm.query,
                        onValueChange = { vm.query = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Что ищем?") },
                        placeholder = { Text("например: лес после дождя") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(vm.mode == "all", { vm.mode = "all" }, "Все")
                        FilterChip(vm.mode == "photo", { vm.mode = "photo" }, "Фото")
                        FilterChip(vm.mode == "video", { vm.mode = "video" }, "Видео")
                        Button(onClick = vm::search, enabled = !vm.loading) { Text("Искать") }
                    }
                    Spacer(Modifier.height(10.dp))
                } else {
                    Text("Избранное", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(10.dp))
                }

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
                    items(visibleItems) { item ->
                        MediaCard(
                            item = item,
                            favorite = item.key in vm.favorites,
                            onFavorite = { vm.toggleFavorite(item) },
                            onOpen = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.sourceUrl)))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MediaCard(item: MediaItem, favorite: Boolean, onFavorite: () -> Unit, onOpen: () -> Unit) {
    Card(Modifier.clickable(onClick = onOpen)) {
        Column {
            AsyncImage(
                model = item.previewUrl.ifBlank { null },
                contentDescription = item.title,
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentScale = ContentScale.Crop
            )
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(vertical = 7.dp)) {
                    Text(item.source, style = MaterialTheme.typography.labelLarge)
                    Text(if (item.isVideo) "Видео" else "Фото", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        imageVector = if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Избранное"
                    )
                }
            }
        }
    }
}

@Composable
fun FilterChip(selected: Boolean, onClick: () -> Unit, text: String) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(text) })
}
