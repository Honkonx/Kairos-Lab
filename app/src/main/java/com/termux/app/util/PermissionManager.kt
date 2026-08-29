package com.termux.app.util

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionManager {

    @JvmStatic fun requestAll(activity: AppCompatActivity) {
        val prefs = activity.getSharedPreferences("kairos_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("storage_permission_requested", false)) {
            requestStorageStep1(activity)
            prefs.edit().putBoolean("storage_permission_requested", true).apply()
        }
        requestNotifications(activity)
    }

    @JvmStatic fun requestStorageStep1(activity: AppCompatActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                101
            )
        } else {
            requestStorageStep2(activity)
        }
    }

    @JvmStatic fun requestStorageStep2(activity: AppCompatActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(activity)
                    .setTitle("Permiso de archivos")
                    .setMessage(
                        "Kairos necesita acceso a todos los archivos para gestionar " +
                        "los módulos del stack IA y leer los scripts de instalación."
                    )
                    .setPositiveButton("Conceder permiso") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:${activity.packageName}")
                        activity.startActivity(intent)
                    }
                    .setNegativeButton("Ahora no", null)
                    .setCancelable(false)
                    .show()
            }
        }
    }

    fun requestNotifications(activity: AppCompatActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    activity, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    102
                )
            }
        }
    }

    fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else true

    fun hasNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        else true
}
