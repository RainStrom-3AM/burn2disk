package com.burnto.disk.di

import android.content.Context
import com.burnto.disk.data.iso.ChecksumCalculator
import com.burnto.disk.data.iso.IsoDetector
import com.burnto.disk.data.iso.WimSplitter
import com.burnto.disk.data.sdcard.SdCardBurnEngine
import com.burnto.disk.data.sdcard.SdCardManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Application-wide singletons. Repositories and managers that take only
 * [Context] are constructor-injected via @Inject; this module covers the types
 * with no Hilt-visible constructor (OkHttp) and the simple stateless helpers.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    @Provides
    @Singleton
    fun provideChecksumCalculator(): ChecksumCalculator = ChecksumCalculator()

    @Provides
    @Singleton
    fun provideIsoDetector(): IsoDetector = IsoDetector()

    @Provides
    @Singleton
    fun provideSdCardManager(@ApplicationContext context: Context): SdCardManager =
        SdCardManager(context)

    @Provides
    @Singleton
    fun provideSdCardBurnEngine(@ApplicationContext context: Context): SdCardBurnEngine =
        SdCardBurnEngine(context)

    @Provides
    @Singleton
    fun provideWimSplitter(@ApplicationContext context: Context): WimSplitter =
        WimSplitter(context)
}
