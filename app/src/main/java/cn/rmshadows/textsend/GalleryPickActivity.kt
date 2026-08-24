package cn.rmshadows.textsend

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import protocol.Protocol
import java.util.concurrent.Executors

/** 应用内相册：先按文件夹，再多选图片/视频。 */
class GalleryPickActivity : AppCompatActivity() {
    private val selected = LinkedHashSet<Uri>()
    private val albums = ArrayList<Album>()
    private val allItems = ArrayList<MediaItem>()
    private val visible = ArrayList<MediaItem>()
    private val thumbs = HashMap<Uri, Bitmap>()
    private val pool = Executors.newFixedThreadPool(3)
    private val adapter = GridAdapter()
    private val albumGrid = GridLayoutManager(this, 2)
    private val photoGrid = GridLayoutManager(this, 3)
    private lateinit var grid: RecyclerView
    private lateinit var done: Button
    private lateinit var back: Button
    private lateinit var title: TextView
    private var showingAlbums = true
    private var currentAlbum = "选择相册"
    private var max = Protocol.FILE_BATCH_MAX

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeSettings.applyTo(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery_pick)
        max = intent.getIntExtra(EXTRA_MAX, Protocol.FILE_BATCH_MAX).coerceAtLeast(1)
        title = findViewById(R.id.galleryTitle)
        done = findViewById(R.id.galleryDone)
        back = findViewById(R.id.galleryBack)
        grid = findViewById(R.id.galleryGrid)
        grid.layoutManager = albumGrid
        grid.adapter = adapter
        done.setOnClickListener { finishPick() }
        back.setOnClickListener { showAlbums() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!showingAlbums) {
                    showAlbums()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        loadMedia()
    }

    override fun onDestroy() {
        super.onDestroy()
        pool.shutdownNow()
    }

    private fun loadMedia() {
        val found = ArrayList<MediaItem>()
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME
        )
        val selection =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        try {
            contentResolver.query(
                uri, projection, selection, args,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val bucketCol = c.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_ID)
                val nameCol = c.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val type = c.getInt(typeCol)
                    val itemUri = if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    } else {
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    }
                    val bucketId = if (bucketCol >= 0 && !c.isNull(bucketCol)) c.getLong(bucketCol) else 0L
                    var bucket = if (nameCol >= 0 && !c.isNull(nameCol)) c.getString(nameCol) else null
                    if (bucket.isNullOrBlank()) {
                        bucket = "其他"
                    }
                    found.add(MediaItem(itemUri, bucketId, bucket))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        allItems.clear()
        allItems.addAll(found)
        val grouped = LinkedHashMap<Long, Album>()
        for (item in allItems) {
            val cur = grouped[item.bucketId]
            if (cur == null) {
                grouped[item.bucketId] = Album(item.bucketId, item.bucket, item.uri, 1)
            } else {
                grouped[item.bucketId] = cur.copy(count = cur.count + 1)
            }
        }
        albums.clear()
        albums.addAll(grouped.values.sortedByDescending { it.count })
        if (allItems.isEmpty()) {
            Toast.makeText(this, "相册是空的或没有读取权限", Toast.LENGTH_SHORT).show()
        }
        showAlbums()
    }

    private fun showAlbums() {
        showingAlbums = true
        currentAlbum = "选择相册"
        back.visibility = View.GONE
        visible.clear()
        grid.layoutManager = albumGrid
        adapter.notifyDataSetChanged()
        refreshTitle()
    }

    private fun openAlbum(album: Album) {
        showingAlbums = false
        currentAlbum = album.name
        back.visibility = View.VISIBLE
        visible.clear()
        visible.addAll(allItems.filter { it.bucketId == album.id })
        grid.layoutManager = photoGrid
        adapter.notifyDataSetChanged()
        refreshTitle()
    }

    private fun toggle(uri: Uri) {
        if (selected.contains(uri)) {
            selected.remove(uri)
        } else {
            if (selected.size >= max) {
                Toast.makeText(this, "最多选 $max 个", Toast.LENGTH_SHORT).show()
                return
            }
            selected.add(uri)
        }
        refreshTitle()
        adapter.notifyDataSetChanged()
    }

    private fun refreshTitle() {
        val n = selected.size
        title.text = if (n == 0) currentAlbum else "$currentAlbum · 已选 $n"
        done.isEnabled = n > 0
        done.text = if (n == 0) "完成" else "完成($n)"
    }

    private fun finishPick() {
        if (selected.isEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val list = ArrayList(selected)
        val data = Intent()
        data.putParcelableArrayListExtra(EXTRA_URIS, list)
        val clip = android.content.ClipData.newUri(contentResolver, "media", list[0])
        for (i in 1 until list.size) {
            clip.addItem(android.content.ClipData.Item(list[i]))
        }
        data.clipData = clip
        data.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setResult(RESULT_OK, data)
        finish()
    }

    private fun loadThumb(uri: Uri, view: ImageView) {
        val cached = thumbs[uri]
        if (cached != null && !cached.isRecycled) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        view.tag = uri
        pool.execute {
            val bmp = try {
                if (Build.VERSION.SDK_INT >= 29) {
                    contentResolver.loadThumbnail(uri, Size(256, 256), null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(contentResolver, uri)
                }
            } catch (_: Exception) {
                null
            }
            if (bmp != null) {
                thumbs[uri] = bmp
            }
            runOnUiThread {
                if (view.tag == uri && bmp != null) {
                    view.setImageBitmap(bmp)
                }
            }
        }
    }

    private inner class GridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int): Int = if (showingAlbums) 0 else 1

        override fun getItemCount(): Int = if (showingAlbums) albums.size else visible.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                AlbumHolder(parent)
            } else {
                ThumbHolder(parent)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AlbumHolder) {
                val album = albums[position]
                holder.name.text = album.name
                holder.count.text = "${album.count} 项"
                loadThumb(album.cover, holder.cover)
                holder.itemView.setOnClickListener { openAlbum(album) }
            } else if (holder is ThumbHolder) {
                val uri = visible[position].uri
                val on = selected.contains(uri)
                holder.mask.visibility = if (on) View.VISIBLE else View.GONE
                holder.check.visibility = if (on) View.VISIBLE else View.GONE
                loadThumb(uri, holder.thumb)
                holder.itemView.setOnClickListener { toggle(uri) }
            }
        }
    }

    private inner class AlbumHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_album, parent, false)
    ) {
        val cover: ImageView = itemView.findViewById(R.id.albumCover)
        val name: TextView = itemView.findViewById(R.id.albumName)
        val count: TextView = itemView.findViewById(R.id.albumCount)
    }

    private inner class ThumbHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_thumb, parent, false)
    ) {
        val thumb: ImageView = itemView.findViewById(R.id.thumb)
        val mask: View = itemView.findViewById(R.id.selectedMask)
        val check: TextView = itemView.findViewById(R.id.check)
    }

    private data class MediaItem(val uri: Uri, val bucketId: Long, val bucket: String)
    private data class Album(val id: Long, val name: String, val cover: Uri, val count: Int)

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_MAX = "max"
    }
}
