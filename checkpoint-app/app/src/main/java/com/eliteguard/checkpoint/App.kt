package com.eliteguard.checkpoint

import android.app.Application
import android.content.Context
import android.provider.Settings
import com.eliteguard.checkpoint.data.Db
import com.eliteguard.checkpoint.data.Repository
import com.eliteguard.checkpoint.data.Session
import com.eliteguard.checkpoint.net.SupabaseClient

class App : Application() {

    lateinit var session: Session
        private set
    lateinit var db: Db
        private set
    lateinit var api: SupabaseClient
        private set
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        session = Session(this)
        db = Db(this)
        api = SupabaseClient(session)
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        repo = Repository(db, api, session, deviceId)
    }

    companion object {
        fun get(context: Context): App = context.applicationContext as App
    }
}
