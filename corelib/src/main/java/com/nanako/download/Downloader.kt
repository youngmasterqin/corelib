package com.nanako.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.text.TextUtils
import android.webkit.MimeTypeMap
import com.nanako.download.Downloader.Status.Existed
import com.nanako.download.Downloader.Status.Progress
import com.nanako.download.Downloader.Status.Failed
import java.io.File
import java.lang.Exception
import java.lang.StringBuilder
import java.util.ArrayList
import com.nanako.log.Log.Companion.LOG

class Downloader private constructor(private val mContext: Context) {
    private val listeners: MutableList<Listener> = ArrayList()
    private val uiHandler = Handler()
    private val backHandler: Handler
    private lateinit var downloadManager: DownloadManager
    private val tasks: MutableList<Task> = ArrayList()

    fun addTask(task: Task) {
        backHandler.post {
            synchronized(tasks) {
                if (!hasTask(task.downloadUrl)) {
                    try {
                        doAddTask(task)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        LOG.e(e)
                    }
                }
            }
        }
    }

    fun hasTask(downloadUrl: String): Boolean {
        for (task in tasks) {
            if (task.downloadUrl == downloadUrl) {
                return true
            }
        }
        return false
    }

    private fun doAddTask(task: Task) {
        val file = File(task.getFileTargetDestination(mContext))
        if (file.exists()) {
            if (!task.isReDownloadWhenExist) {
                LOG.w("target file[${file.absolutePath}] exist")
                notifyListener(Existed(task))
                return
            }
        }
        val request = DownloadManager.Request(Uri.parse(task.downloadUrl))
        request.setAllowedOverRoaming(false)
        request.setMimeType(
            MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(
                    MimeTypeMap.getFileExtensionFromUrl(
                        task.downloadUrl
                    )
                )
        )

        //?????????????????????????????????????????????
        request.setNotificationVisibility(if (task.isShowNotification) DownloadManager.Request.VISIBILITY_VISIBLE else DownloadManager.Request.VISIBILITY_HIDDEN)
        request.setVisibleInDownloadsUi(task.isShowNotification)
        val subPath: String
        subPath = if (TextUtils.isEmpty(task.subDir)) {
            task.fileName
        } else {
            task.subDir + File.separator + task.fileName
        }
        if (task.isFileSaveToPublicStorage) {
            request.setDestinationInExternalPublicDir(task.dirType, subPath)
        } else {
            request.setDestinationInExternalFilesDir(mContext, task.dirType, subPath)
        }
        task.downloadId = downloadManager.enqueue(request)
        tasks.add(task)
        LOG.d("add download task???${Task.toString(mContext, task)}")
        checkMonitorDownloadStatus()
    }

    fun removeTask(task: Task, removeFile: Boolean) {
        backHandler.post { synchronized(tasks) { doRemoveTask(task, removeFile) } }
    }

    private fun doRemoveTask(task: Task, removeFile: Boolean) {
        for (t in tasks) {
            if (t.downloadId == task.downloadId) {
                tasks.remove(t)
                if (removeFile) {
                    downloadManager.remove(t.downloadId)
                }
                LOG.d("remove download task???${Task.toString(mContext, t)}")
                break
            }
        }
    }

    private fun checkMonitorDownloadStatus() {
        backHandler.removeCallbacks(mCheckDownloadStatusRunn)
        if (tasks.isEmpty()) {
            LOG.w("no download task exist, not loop check downlaod status")
        } else {
            backHandler.postDelayed(mCheckDownloadStatusRunn, 1000)
        }
    }

    private val mCheckDownloadStatusRunn = Runnable {
        synchronized(tasks) {
            val tasksSuccess: MutableList<Task> = ArrayList()
            val tasksFailed: MutableList<Task> = ArrayList()
            val query = DownloadManager.Query()
            for (task in tasks) {
                query.setFilterById(task.downloadId)
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val status =
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    var reason: Int
                    when (status) {
                        DownloadManager.STATUS_PAUSED -> {
                            reason =
                                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            LOG.w(
                                "download paused:${
                                    Task.toString(
                                        mContext,
                                        task
                                    )
                                }, reason:${reason}"
                            )
                            LOG.w("download delayed:${Task.toString(mContext, task)}")
                            val soFar =
                                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val total =
                                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            LOG.v(
                                "downloading:${
                                    Task.toString(
                                        mContext,
                                        task
                                    )
                                },progress:$soFar/$total"
                            )
                            notifyListener(Progress(task, soFar, total))
                        }
                        DownloadManager.STATUS_PENDING -> {
                            LOG.w("download delayed:${Task.toString(mContext, task)}")
                            val soFar =
                                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val total =
                                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            LOG.v(
                                "downloading:${
                                    Task.toString(
                                        mContext,
                                        task
                                    )
                                },progress:${soFar}/${total}"
                            )
                            notifyListener(Progress(task, soFar, total))
                        }
                        DownloadManager.STATUS_RUNNING -> {
                            val soFar =
                                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val total =
                                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            LOG.v(
                                "downloading:${
                                    Task.toString(
                                        mContext,
                                        task
                                    )
                                },progress:${soFar}/${total}"
                            )
                            notifyListener(Progress(task, soFar, total))
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val destinationUri = cursor.getString(
                                cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_LOCAL_URI
                                )
                            )
                            task.fileRealDestinationUri = destinationUri
                            LOG.i("download success:${Task.toString(mContext, task)}")
                            tasksSuccess.add(task)
                            notifyListener(Status.Success(task))
                        }
                        DownloadManager.STATUS_FAILED -> {
                            reason =
                                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            LOG.e(
                                "download failed:${
                                    Task.toString(
                                        mContext,
                                        task
                                    )
                                }, reason:${reason}"
                            )
                            tasksFailed.add(task)
                            notifyListener(Failed(task))
                        }
                    }
                }
            }
            for (task in tasksSuccess) {
                doRemoveTask(task, false)
            }
            for (task in tasksFailed) {
                doRemoveTask(task, true)
            }
            checkMonitorDownloadStatus()
        }
    }

    init {
        val thread = HandlerThread("xdownloader")
        thread.start()
        backHandler = Handler(thread.looper)
        downloadManager = mContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    class Task(
        /**
         * ????????????
         */
        val downloadUrl: String,
        /**
         * ???????????????
         */
        val dirType: String,
        /**
         * ?????????
         */
        val subDir: String,
        /**
         * ????????????????????????
         */
        var fileName: String,
        /**
         * ????????????????????????
         */
        val isShowNotification: Boolean,
        /**
         * ?????????????????????????????????android10???????????????????????????????????????????????????????????????????????????????????????
         */
        val isReDownloadWhenExist: Boolean
    ) {
        /**
         * ??????id???DownloadManager??????
         */
        var downloadId: Long = 0

        /**
         * true,??????????????????????????????????????????????????????mDirType???????????????????????????????????????false,????????????????????????app????????????????????????
         */
        var isFileSaveToPublicStorage = false

        /**
         * ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????
         */
        var fileRealDestinationUri: String? = null
        var type = 0
            private set
        var extra: Any? = null
            private set

        fun setType(type: Int): Task {
            this.type = type
            return this
        }

        fun setExtra(extra: Any?): Task {
            this.extra = extra
            return this
        }

        fun getFileTargetDestination(context: Context): String {
            val subPath =
                (if (TextUtils.isEmpty(subDir)) "" else subDir + File.separator) + fileName
            return if (isFileSaveToPublicStorage) {
                Environment.getExternalStoragePublicDirectory(dirType)
                    .toString() + File.separator + subPath
            } else {
                context.getExternalFilesDir(dirType)!!.absolutePath + File.separator + subPath
            }
        }

        companion object {
            fun createInstance(downloadUrl: String, subDir: String, fileName: String): Task {
                return Task(
                    downloadUrl,
                    Environment.DIRECTORY_DOWNLOADS, subDir,
                    fileName, false, true
                )
            }

            fun toString(context: Context, task: Task): String {
                val sb = StringBuilder()
                sb.append("id=").append(task.downloadId)
                sb.append(",").append("url=").append(task.downloadUrl)
                sb.append(",").append("target=").append(task.getFileTargetDestination(context))
                if (!TextUtils.isEmpty(task.fileRealDestinationUri)) {
                    sb.append(",").append("real=").append(task.fileRealDestinationUri)
                }
                return sb.toString()
            }
        }
    }

    open class Status(val task: Task) {

        class Progress(task: Task, val downloadSize: Int, val totalSize: Int) : Status(task)
        class Success(task: Task) : Status(task)
        class Failed(task: Task) : Status(task)
        class Existed(task: Task) : Status(task)
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyListener(status: Status) {
        uiHandler.post {
            synchronized(listeners) {
                val s = listeners.size
                for (i in 0 until s) {
                    if (listeners[i].onDownload(status)) {
                        break
                    }
                }
            }
        }
    }

    interface Listener {
        fun onDownload(status: Status?): Boolean
    }

    companion object {
        lateinit var instance: Downloader
            private set

        fun init(context: Context) {
            instance = Downloader(context)
        }
    }
}