package com.example.telegrambackup

import android.app.Application
import android.util.Log

class TelegramBackupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // تهيئة عميل تيليجرام عند بدء التطبيق
            val telegramManager = TelegramManager.getInstance(this)
            Log.d("TelegramBackupApp", "تم تهيئة TelegramManager بنجاح")
        } catch (e: Exception) {
            Log.e("TelegramBackupApp", "خطأ أثناء تهيئة TelegramManager", e)
        }
    }
}