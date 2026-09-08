package com.sosauce.chocola.di

import androidx.room.Room
import com.sosauce.chocola.data.AbstractTracksScanner
import com.sosauce.chocola.data.LyricsParser
import com.sosauce.chocola.data.datastore.UserPreferences
import com.sosauce.chocola.data.playlist.PLAYLIST_DATABASE_MIGRATIONS
import com.sosauce.chocola.data.playlist.PlaylistCleanup
import com.sosauce.chocola.data.playlist.PlaylistDatabase
import com.sosauce.chocola.data.repositories.FoldersRepository
import com.sosauce.chocola.data.repositories.IDRepositories
import com.sosauce.chocola.data.repositories.SafManager
import com.sosauce.chocola.data.widgets.WidgetsHelper
import com.sosauce.chocola.domain.EqualizerManager
import com.sosauce.chocola.domain.helpers.AndroidAutoHelper
import com.sosauce.chocola.presentation.components.MusicViewModel
import com.sosauce.chocola.presentation.components.dialogs.DeletionViewModel
import com.sosauce.chocola.presentation.components.dialogs.tracksDetails.TracksDetailsDialogViewModel
import com.sosauce.chocola.presentation.screens.album.AlbumDetailsViewModel
import com.sosauce.chocola.presentation.screens.album.AlbumsViewModel
import com.sosauce.chocola.presentation.screens.artist.ArtistDetailsViewModel
import com.sosauce.chocola.presentation.screens.artist.ArtistsViewModel
import com.sosauce.chocola.presentation.screens.lyrics.LyricsEditorViewModel
import com.sosauce.chocola.presentation.screens.main.MainViewModel
import com.sosauce.chocola.presentation.screens.metadata.MetadataViewModel
import com.sosauce.chocola.presentation.screens.playlists.PlaylistDetailsViewModel
import com.sosauce.chocola.presentation.screens.playlists.PlaylistViewModel
import com.sosauce.chocola.presentation.screens.quickplay.QuickPlayViewModel
import com.sosauce.chocola.presentation.screens.settings.FoldersViewModel
import com.sosauce.chocola.presentation.screens.settings.PlaybackSettingsViewModel
import com.sosauce.chocola.presentation.screens.settings.SettingsLibraryViewModel
import com.sosauce.chocola.presentation.screens.transformer.TransformerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import lol.alphaliu01.runningmusic.analysis.AnalysisConfig
import lol.alphaliu01.runningmusic.analysis.AubioTempoAnalyser
import lol.alphaliu01.runningmusic.analysis.BpmAnalyser
import lol.alphaliu01.runningmusic.analysis.BpmAnalysisManager
import lol.alphaliu01.runningmusic.analysis.BpmTagReader
import lol.alphaliu01.runningmusic.analysis.PcmDecoder
import lol.alphaliu01.runningmusic.analysis.TempoAnalyser
import lol.alphaliu01.runningmusic.analysis.ui.TempoAnalysisViewModel
import lol.alphaliu01.runningmusic.library.TrackMetadataRepository
import lol.alphaliu01.runningmusic.running.CadenceTracker
import lol.alphaliu01.runningmusic.running.RunningModeManager
import lol.alphaliu01.runningmusic.running.ui.RunningViewModel
import lol.alphaliu01.runningmusic.steps.StepRecorder
import lol.alphaliu01.runningmusic.steps.StepSensors
import lol.alphaliu01.runningmusic.steps.dev.StepRecorderViewModel
import org.koin.android.ext.koin.androidApplication
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    // The database itself is exposed now, not just one DAO off the end of the
    // builder, because there is more than one DAO to hand out and they have to
    // come from the same instance.
    single {
        Room.databaseBuilder(
            context = androidApplication(),
            klass = PlaylistDatabase::class.java,
            name = "playlist.db"
        )
            .addMigrations(*PLAYLIST_DATABASE_MIGRATIONS)
            //.addCallback(DEFAULT_PLAYLISTS_CALLBACK)
            .build()
    }

    single { get<PlaylistDatabase>().dao }
    single { get<PlaylistDatabase>().trackMetadataDao }

    single { CoroutineScope(Dispatchers.IO + SupervisorJob()) }

    singleOf(::AbstractTracksScanner)
    singleOf(::LyricsParser)
    singleOf(::FoldersRepository)
    singleOf(::SafManager)
    singleOf(::UserPreferences)
    singleOf(::EqualizerManager)
    singleOf(::AndroidAutoHelper)
    singleOf(::WidgetsHelper)
    singleOf(::IDRepositories)
    singleOf(::PlaylistCleanup)
    singleOf(::TrackMetadataRepository)

    // Tempo analysis. The estimator is bound to the interface rather than to its
    // own type, so nothing above it names aubio and swapping the engine is one
    // line here.
    single { AnalysisConfig() }
    single<TempoAnalyser> { AubioTempoAnalyser() }
    singleOf(::PcmDecoder)
    singleOf(::BpmTagReader)
    single { BpmAnalyser(decoder = get(), analyser = get(), tags = get(), config = get()) }
    single { BpmAnalysisManager(androidApplication()) }

    // Running mode. A singleton rather than view model state because a run has
    // to keep re-speeding the player after the activity that started it is gone.
    singleOf(::RunningModeManager)

    // Holds a sensor registration for the length of a run, so it has to outlive
    // any screen for the same reason the manager does.
    singleOf(::CadenceTracker)

    singleOf(::StepSensors)

    // Debug-only step-detector spike. Registered unconditionally because the
    // dev screen that reaches it is gated on BuildConfig.DEBUG; nothing
    // constructs it in a release build.
    singleOf(::StepRecorder)


    viewModelOf(::MusicViewModel)
    viewModelOf(::MetadataViewModel)
    viewModelOf(::PlaylistViewModel)
    viewModelOf(::PlaylistDetailsViewModel)
    viewModelOf(::QuickPlayViewModel)
    viewModelOf(::ArtistsViewModel)
    viewModelOf(::ArtistDetailsViewModel)
    viewModelOf(::AlbumsViewModel)
    viewModelOf(::AlbumDetailsViewModel)
    viewModelOf(::MainViewModel)
    viewModelOf(::FoldersViewModel)
    viewModelOf(::PlaybackSettingsViewModel)
    viewModelOf(::TransformerViewModel)
    viewModelOf(::DeletionViewModel)
    viewModelOf(::LyricsEditorViewModel)
    viewModelOf(::SettingsLibraryViewModel)
    viewModelOf(::TracksDetailsDialogViewModel)
    viewModelOf(::TempoAnalysisViewModel)
    viewModelOf(::RunningViewModel)
    viewModelOf(::StepRecorderViewModel)
}