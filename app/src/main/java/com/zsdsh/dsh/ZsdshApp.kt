package com.zsdsh.dsh

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.zsdsh.dsh.engine.DshEngine
import com.zsdsh.dsh.privilege.PrivilegeManager
import com.zsdsh.dsh.privilege.PrivilegeServer

class ZsdshApp : Application() {
    lateinit var privilege: PrivilegeManager
        private set
    lateinit var engine: DshEngine
        private set
    private lateinit var privilegeServer: PrivilegeServer

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        instance = this
        privilege = PrivilegeManager(this)
        engine = DshEngine(this)
        privilegeServer = PrivilegeServer(privilege)
        privilegeServer.start()
    }

    companion object {
        lateinit var instance: ZsdshApp
            private set
    }
}
