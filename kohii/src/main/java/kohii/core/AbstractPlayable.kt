/*
 * Copyright (c) 2019 Nam Nguyen, nam@ene.im
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kohii.core

import kohii.core.Master.MemoryMode
import kohii.core.Master.MemoryMode.AUTO
import kohii.core.Master.MemoryMode.BALANCED
import kohii.core.Master.MemoryMode.HIGH
import kohii.core.Master.MemoryMode.INFINITE
import kohii.core.Master.MemoryMode.LOW
import kohii.core.Master.MemoryMode.NORMAL
import kohii.logInfo
import kohii.logWarn
import kohii.media.Media
import kohii.media.PlaybackInfo
import kohii.media.VolumeInfo
import kohii.v1.Bridge
import kotlin.properties.Delegates

abstract class AbstractPlayable<RENDERER : Any>(
  protected val master: Master,
  media: Media,
  config: Config,
  public final override val bridge: Bridge<RENDERER>
) : Playable(media, config), Playback.Callback {

  override val tag: Any = config.tag

  override fun toString(): String {
    return "Playable@$tag"
  }

  // Ensure the preparation for the playback
  override fun onReady() {
    "Playable#onReady $this".logInfo()
    bridge.ready()
  }

  override fun onReset() {
    bridge.reset(true)
  }

  override fun onConfigChange(): Boolean {
    "Playable#onConfigChange $this".logInfo()
    return true
  }

  override fun onPrepare(loadSource: Boolean) {
    "Playable#onPrepare $loadSource $this".logInfo()
    bridge.prepare(loadSource)
  }

  private var playRequested: Boolean = false

  override fun onPlay() {
    "Playable#onPlay $this".logWarn()
    playback?.onPlay()
    if (!playRequested) {
      playRequested = true
      bridge.play()
    }
  }

  override fun onPause() {
    "Playable#onPause $this".logWarn()
    if (playRequested) {
      playRequested = false
      bridge.pause()
    }
    playback?.onPause()
  }

  override fun onRelease() {
    "Playable#onRelease $this".logInfo()
    bridge.release()
  }

  private val memoryMode: MemoryMode
    get() = (manager as? Manager)?.memoryMode ?: LOW

  override var manager: PlayableManager? by Delegates.observable<PlayableManager?>(
      null,
      onChange = { _, from, to ->
        if (from === to) return@observable
        "Playable#manager $from --> $to, $this".logInfo()
        if (to == null) {
          master.trySavePlaybackInfo(this)
          master.tearDown(this, false)
        } else if (from === null) {
          master.tryRestorePlaybackInfo(this)
        }
      }
  )

  @Suppress("IfThenToElvis")
  override var playback: Playback? by Delegates.observable<Playback?>(
      null,
      onChange = { _, from, to ->
        if (from === to) return@observable
        "Playable#playback $from --> $to, $this".logInfo()
        if (from != null) {
          bridge.removeErrorListener(from)
          bridge.removeEventListener(from)
          from.removeCallback(this)
          if (from.playable === this) from.playable = null
        }

        this.manager =
          if (to != null) {
            to.manager
          } else {
            val configChange =
              if (from != null) {
                from.manager.group.activity.isChangingConfigurations
              } else {
                false
              }

            if (!configChange) null
            else if (!onConfigChange()) {
              // on config change, if the Playable doesn't support, we need to pause the Video.
              onPause() // TODO check why it doesn't work for YouTube demo.
              null
            } else {
              master // to prevent the Playable from being destroyed when Manager is null.
            }
          }

        if (to != null) {
          to.addCallback(this)
          to.config.callbacks.forEach { cb -> to.addCallback(cb) }
          bridge.addEventListener(to)
          bridge.addErrorListener(to)
          to.playable = this
        }
      }
  )

  override val playerState: Int
    get() = bridge.playbackState

  // Playback.Callback

  override fun onActive(playback: Playback) {
    "Playable#onActive $playback, $this".logInfo()
    require(playback === this.playback)
    master.tryRestorePlaybackInfo(this)
    master.preparePlayable(this, playback.config.preload)
  }

  override fun onInActive(playback: Playback) {
    "Playable#onInActive $playback, $this".logInfo()
    require(playback === this.playback)
    val configChange = playback.manager.group.activity.isChangingConfigurations
    if (!configChange) {
      master.trySavePlaybackInfo(this)
      master.releasePlayable(this)
    }
  }

  override fun onAdded(playback: Playback) {
    "Playable#onAdded $playback, $this".logInfo()
    bridge.repeatMode = playback.config.repeatMode
    bridge.setVolumeInfo(playback.volumeInfo)
  }

  override fun onRemoved(playback: Playback) {
    "Playable#onRemoved $playback, $this".logInfo()
    require(playback === this.playback)
    this.playback = null // Will also clear current Manager.
  }

  protected abstract fun shouldAttachRenderer(renderer: Any?)

  protected abstract fun shouldDetachRenderer()

  override fun considerRequestRenderer(playback: Playback) {
    "Playable#considerRequestRenderer $playback, $this".logInfo()
    require(playback === this.playback)
    if (bridge.renderer == null || manager !== playback.manager) { // Only request for Renderer if we do not have one.
      val renderer = playback.manager.requestRenderer(playback, this)
      shouldAttachRenderer(renderer)
    }
  }

  override fun considerReleaseRenderer(playback: Playback) {
    "Playable#considerReleaseRenderer $playback, $this".logInfo()
    require(this.playback == null || this.playback === playback)
    if (bridge.renderer != null) { // Only release the Renderer if we do have one to release.
      playback.manager.releaseRenderer(playback, this)
      shouldDetachRenderer()
    }
  }

  override fun onDistanceChanged(
    playback: Playback,
    from: Int,
    to: Int
  ) {
    "Playable#onDistanceChanged $playback, $from --> $to, $this".logInfo()
    if (to == 0) {
      master.tryRestorePlaybackInfo(this)
      master.preparePlayable(this, playback.config.preload)
    } else {
      val memoryMode = master.preferredMemoryMode(this.memoryMode)
      val distanceToRelease =
        when (memoryMode) {
          AUTO, LOW -> 1
          NORMAL -> 2
          BALANCED -> 2 // Same as 'NORMAL', but will keep the 'relative' Playback alive.
          HIGH -> 8
          INFINITE -> Int.MAX_VALUE - 1
        }
      if (to >= distanceToRelease) {
        master.trySavePlaybackInfo(this)
        master.releasePlayable(this)
      } else {
        if (memoryMode != BALANCED) {
          bridge.reset(false)
        }
      }
    }
  }

  override fun onVolumeInfoChange(
    playback: Playback,
    from: VolumeInfo,
    to: VolumeInfo
  ) {
    "Playable#onVolumeInfoChange $playback, $from --> $to, $this".logInfo()
    bridge.setVolumeInfo(to)
  }

  override var playbackInfo: PlaybackInfo
    get() = bridge.playbackInfo
    set(value) {
      "Playable#playbackInfo setter $value, $this".logInfo()
      bridge.playbackInfo = value
    }

  override fun onUnbind(playback: Playback) {
    "Playable#onUnbind $playback, $this".logInfo()
    if (this.playback === playback) {
      playback.manager.removePlayback(playback)
    }
  }
}