// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.sim.generic

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.fragment.app.Fragment
import java.io.File

/** 「文件」Tab —— 全屏展示主端发来的图 / 视频 / 文件名 */
class FileDisplayFragment : Fragment() {

    private var imageView: ImageView? = null
    private var videoView: VideoView? = null
    private var fileNameView: TextView? = null
    private var tvEmptyDisplay: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_file_display, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        imageView = view.findViewById(R.id.imageView)
        videoView = view.findViewById(R.id.videoView)
        fileNameView = view.findViewById(R.id.fileNameView)
        tvEmptyDisplay = view.findViewById(R.id.tvEmptyDisplay)
        imageView?.setOnClickListener {
            (activity as? MainActivity)?.showFullscreenImage()
        }
        // 恢复最近一次收到的文件：切到本 Tab 时 View 会重建，内容不能丢
        (activity as? MainActivity)?.renderLastFile(this)
    }

    internal fun showEmpty() {
        imageView?.visibility = View.GONE
        videoView?.visibility = View.GONE
        fileNameView?.visibility = View.GONE
        tvEmptyDisplay?.visibility = View.VISIBLE
    }

    fun showBitmap(bmp: Bitmap) {
        imageView?.setImageBitmap(bmp)
        imageView?.visibility = View.VISIBLE
        videoView?.visibility = View.GONE
        fileNameView?.visibility = View.GONE
        tvEmptyDisplay?.visibility = View.GONE
    }

    fun showFileName(fileName: String, size: Int) {
        val act = activity as? MainActivity ?: return
        fileNameView?.text = "文件：$fileName\n${act.formatSize(size.toLong())}"
        fileNameView?.visibility = View.VISIBLE
        imageView?.visibility = View.GONE
        videoView?.visibility = View.GONE
        tvEmptyDisplay?.visibility = View.GONE
    }

    fun playVideo(file: File) {
        videoView?.setVideoURI(Uri.fromFile(file))
        videoView?.setOnPreparedListener { mp ->
            mp.isLooping = true
            videoView?.start()
        }
        videoView?.visibility = View.VISIBLE
        imageView?.visibility = View.GONE
        fileNameView?.visibility = View.GONE
        tvEmptyDisplay?.visibility = View.GONE
    }
}