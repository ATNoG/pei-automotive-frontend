package com.example.test_app_2

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class MyCarAppService : CarAppService() {
    override fun onCreateSession(): Session {
        return MyCarSession()
    }

    override fun createHostValidator(): HostValidator {
        // Accept all hosts (unsafe for production, fine for testing)
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }
}