import json
import os
import time
import yt_dlp
from urllib.parse import urlparse
from yt_dlp import YoutubeDL

def _check_url(url):
    url = url.strip()
    parsed = urlparse(url)
    if parsed.scheme != "https" or (parsed.hostname or "").lower() not in (
        "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "music.youtube.com"
    ):
        raise ValueError("Нужна ссылка https://youtube.com или https://youtu.be")
    return url

def _options(profile="legacy"):
    opts = {
        "noplaylist": True, "quiet": True, "no_warnings": False,
        "retries": 4, "fragment_retries": 4, "socket_timeout": 35,
    }
    # The extractor's own defaults are deliberately tried first on newer
    # yt-dlp versions. Forced clients can hide adaptive streams.
    clients = {
        "legacy": ["android", "web"],
        "alternate": ["web_safari", "ios", "android"],
        "tv": ["tv", "web"],
    }
    if profile in clients:
        opts["extractor_args"] = {"youtube": {"player_client": clients[profile]}}
    return opts

def _available(info):
    formats = [f for f in (info.get("formats") or []) if f.get("url")]
    combined = [f for f in formats if f.get("ext") == "mp4"
                and f.get("vcodec") not in (None, "none")
                and f.get("acodec") not in (None, "none")]
    video = [f for f in formats if f.get("ext") == "mp4"
             and str(f.get("vcodec") or "").startswith(("avc1", "h264"))
             and f.get("acodec") == "none"]
    audio = [f for f in formats if f.get("ext") in ("m4a", "mp4")
             and str(f.get("acodec") or "").startswith(("mp4a", "aac"))
             and f.get("vcodec") == "none"]
    best_audio = max(audio, key=lambda f: f.get("abr") or f.get("tbr") or 0, default=None)
    # Expose every compatible, URL-backed format rather than hiding variants
    # with the same resolution. The Android muxer supports H.264 + AAC MP4.
    choices = []
    for f in combined:
        height = f.get("height") or 0
        if height > 0 and str(f.get("vcodec") or "").startswith(("avc1", "h264")):
            bitrate = round(f.get("tbr") or 0)
            choices.append({
                "id": "single:" + str(f["format_id"]),
                "label": f"{height}p · MP4 со звуком · {f['format_id']}" +
                         (f" · {bitrate} кбит/с" if bitrate else ""),
                "height": height, "kind": "single",
            })
    if best_audio:
        for f in video:
            height = f.get("height") or 0
            if height > 0:
                bitrate = round(f.get("tbr") or 0)
                choices.append({
                    "id": "merge:" + str(f["format_id"]) + "+" + str(best_audio["format_id"]),
                    "label": f"{height}p · MP4 + AAC · {f['format_id']}" +
                             (f" · {bitrate} кбит/с" if bitrate else ""),
                    "height": height, "kind": "merge",
                })
    choices.sort(key=lambda item: (item["height"], item["kind"] == "single"), reverse=True)
    for f in sorted(audio, key=lambda item: item.get("abr") or item.get("tbr") or 0, reverse=True):
        bitrate = round(f.get("abr") or f.get("tbr") or 0)
        choices.append({
            "id": "audio:" + str(f["format_id"]),
            "label": "M4A · " + str(f["format_id"]) +
                     (f" · {bitrate} кбит/с" if bitrate else ""),
            "height": -1, "kind": "audio",
        })
    return choices

class _DiagnosticLogger:
    def __init__(self, warnings):
        self.warnings = warnings
    def debug(self, message):
        pass
    def warning(self, message):
        if len(self.warnings) < 8:
            self.warnings.append(str(message)[:300])
    def error(self, message):
        self.warning(message)


def _inspect_best(url):
    best_info, best_choices, best_profile = None, [], "legacy"
    failures = []
    reports = []
    for profile in ("auto", "legacy", "alternate", "tv"):
        try:
            warnings = []
            opts = _options(profile)
            opts["logger"] = _DiagnosticLogger(warnings)
            with YoutubeDL(opts) as ydl:
                info = ydl.extract_info(url, download=False)
            choices = _available(info)
            reports.append({"profile": profile, "formats": len(info.get("formats") or []),
                            "max_video": max((fmt.get("height") or 0 for fmt in info.get("formats") or []
                                          if fmt.get("vcodec") not in (None, "none")), default=0),
                            "warnings": warnings[:8]})
            if best_info is None or max((x["height"] for x in choices), default=0) > max((x["height"] for x in best_choices), default=0):
                best_info, best_choices, best_profile = info, choices, profile
            if max((x["height"] for x in best_choices), default=0) >= 2160:
                break
        except Exception as exc:
            failures.append(str(exc)[:180])
            reports.append({"profile": profile, "error": str(exc)[:240]})
    if best_info is None:
        raise RuntimeError("Не удалось получить форматы: " + "; ".join(failures))
    return best_info, best_choices, best_profile, reports

def inspect_video(url):
    info, choices, profile, reports = _inspect_best(_check_url(url))
    for choice in choices:
        choice["id"] = profile + "|" + choice["id"]
    raw_formats = info.get("formats") or []
    diagnostics = []
    for fmt in raw_formats:
        height = fmt.get("height") or 0
        if height:
            diagnostics.append({
                "id": str(fmt.get("format_id") or "?"),
                "height": height,
                "ext": str(fmt.get("ext") or "?"),
                "video": str(fmt.get("vcodec") or "none"),
                "audio": str(fmt.get("acodec") or "none"),
                "url": bool(fmt.get("url")),
            })
    diagnostics.sort(key=lambda item: item["height"], reverse=True)
    return json.dumps({
        "title": info.get("title") or "Видео",
        "channel": info.get("channel") or info.get("uploader") or "YouTube",
        "formats": choices,
        "profile": profile,
        "yt_dlp_version": yt_dlp.version.__version__,
        "reports": reports,
        "diagnostics": diagnostics[:40],
    }, ensure_ascii=False)

def _get_file(info, ydl):
    for item in info.get("requested_downloads") or []:
        path = item.get("filepath")
        if path and os.path.isfile(path):
            return path
    path = ydl.prepare_filename(info)
    if os.path.isfile(path):
        return path
    raise RuntimeError("Файл не найден после скачивания")

def _progress_hook(directory, stage):
    last = [0.0]
    def hook(data):
        if os.path.exists(os.path.join(directory, "cancel.flag")):
            raise RuntimeError("Загрузка отменена пользователем")
        now = time.monotonic()
        if data.get("status") == "downloading" and now - last[0] < 0.35:
            return
        last[0] = now
        total = data.get("total_bytes") or data.get("total_bytes_estimate") or 0
        downloaded = data.get("downloaded_bytes") or 0
        percent = round(100.0 * downloaded / total, 1) if total else -1
        payload = {"stage": stage, "percent": percent,
                   "downloaded": downloaded, "total": total,
                   "speed": data.get("speed") or 0,
                   "eta": data.get("eta")}
        target = os.path.join(directory, "progress.json")
        temp = target + ".tmp"
        with open(temp, "w", encoding="utf-8") as out:
            json.dump(payload, out)
        os.replace(temp, target)
    return hook


def _fetch(url, directory, fmt_id, prefix, profile="legacy"):
    opts = _options(profile)
    opts["progress_hooks"] = [_progress_hook(directory, prefix)]
    opts.update({"format": fmt_id, "outtmpl": os.path.join(directory, prefix + ".%(ext)s"),
                 "overwrites": True, "restrictfilenames": True})
    with YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=True)
        return info, _get_file(info, ydl)

def download_video(url, directory, choice):
    url = _check_url(url)
    os.makedirs(directory, exist_ok=True)
    if os.path.exists(os.path.join(directory, "cancel.flag")):
        raise RuntimeError("Загрузка отменена пользователем")
    if "|" in choice:
        profile, selection = choice.split("|", 1)
    else:
        profile, selection = "legacy", choice
    if profile not in ("auto", "legacy", "alternate", "tv"):
        raise ValueError("Неизвестный профиль загрузки")
    with YoutubeDL(_options(profile)) as ydl:
        info = ydl.extract_info(url, download=False)
    valid = {item["id"] for item in _available(info)}
    if selection not in valid:
        raise ValueError("Формат изменился. Обнови список форматов.")
    kind, fmt = selection.split(":", 1)
    paths = []
    if kind == "merge":
        video_id, audio_id = fmt.split("+", 1)
        _, video = _fetch(url, directory, video_id, "video", profile)
        _, audio = _fetch(url, directory, audio_id, "audio", profile)
        paths = [video, audio]
    elif kind == "audio":
        _, audio = _fetch(url, directory, fmt, "audio", profile)
        paths = ["", audio]
    else:
        _, video = _fetch(url, directory, fmt, "video", profile)
        paths = [video, ""]
    return json.dumps({
        "video": paths[0], "audio": paths[1], "merge": kind == "merge",
        "title": info.get("title") or info.get("id") or "Видео",
        "channel": info.get("channel") or info.get("uploader") or "YouTube",
        "date": info.get("upload_date") or "",
        "description": info.get("description") or "",
    }, ensure_ascii=False)

def latest_channel_videos(channel_url):
    url = _check_url(channel_url).rstrip("/")
    if "/@" not in url and "/channel/" not in url and "/c/" not in url and "/user/" not in url:
        raise ValueError("Нужна ссылка на YouTube-канал")
    if not url.endswith("/videos"):
        url += "/videos"
    opts = _options()
    opts.update({"extract_flat": "in_playlist", "playlistend": 5, "ignoreerrors": True})
    with YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
    if not info:
        raise RuntimeError("Не удалось прочитать последние видео канала")
    result = []
    for item in info.get("entries") or []:
        if not item:
            continue
        video_id = item.get("id")
        link = item.get("url") or ""
        if not link.startswith("https://"):
            link = "https://www.youtube.com/watch?v=" + str(video_id or "")
        if video_id and link.startswith("https://"):
            result.append({"title": item.get("title") or "Видео", "url": link})
        if len(result) == 5:
            break
    return json.dumps(result, ensure_ascii=False)


def inspect_playlist(playlist_url, limit=100):
    """Read playlist entries without downloading media; cap expansion for mobile."""
    url = _check_url(playlist_url)
    parsed = urlparse(url)
    from urllib.parse import parse_qs
    if not parse_qs(parsed.query).get("list") and "/playlist" not in parsed.path:
        raise ValueError("Нужна ссылка YouTube-плейлиста с параметром list")
    limit = max(1, min(int(limit), 100))
    # Playlist metadata should fail quickly on a broken VPN/DNS connection.
    # Video downloads retain their separate retry policy.
    opts = _options("auto")
    opts.update({"noplaylist": False, "extract_flat": "in_playlist",
                 "playlistend": limit, "ignoreerrors": True,
                 "socket_timeout": 12, "retries": 1,
                 "extractor_retries": 1, "fragment_retries": 1})
    with YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
    if not info or not info.get("entries"):
        raise RuntimeError("Плейлист пуст или недоступен")
    entries = []
    seen = set()
    for item in info.get("entries") or []:
        if not item:
            continue
        video_id = str(item.get("id") or "")
        if not video_id or video_id in seen:
            continue
        seen.add(video_id)
        entries.append({"title": item.get("title") or "Видео",
                        "url": "https://www.youtube.com/watch?v=" + video_id})
    if not entries:
        raise RuntimeError("В плейлисте нет доступных видео")
    return json.dumps({"title": info.get("title") or "Плейлист",
                       "entries": entries, "limited": len(entries) >= limit},
                      ensure_ascii=False)
