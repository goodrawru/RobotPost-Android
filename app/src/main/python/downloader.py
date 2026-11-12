import json
import os
import time
from datetime import datetime, timezone
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

def _options(profile="legacy", cookie_file=None, use_cookies=False, js_runtime_path=None):
    opts = {
        "noplaylist": True, "quiet": True, "no_warnings": False,
        "retries": 4, "fragment_retries": 4, "socket_timeout": 35,
        "http_headers": {"Accept-Language": "ru-RU,ru;q=0.9,en;q=0.5"},
    }
    # The extractor's own defaults are deliberately tried first on newer
    # yt-dlp versions. Forced clients can hide adaptive streams.
    clients = {
        "legacy": ["android", "web"],
        "alternate": ["web_safari", "ios", "android"],
        "tv": ["tv", "web"],
    }
    # Ask YouTube for Russian translated metadata when the uploader provides it.
    opts["extractor_args"] = {"youtube": {"lang": ["ru"]}}
    if profile in clients:
        opts["extractor_args"]["youtube"]["player_client"] = clients[profile]
    if use_cookies and cookie_file and os.path.isfile(cookie_file):
        opts["cookiefile"] = cookie_file
    if js_runtime_path and os.path.isfile(js_runtime_path):
        opts["js_runtimes"] = {"quickjs": {"path": js_runtime_path}}
    return opts


def _needs_login(error):
    message = str(error).lower()
    return ("sign in to confirm" in message or "login required" in message
            or "войдите в аккаунт" in message)

def _has_original_marker(fmt):
    note = str(fmt.get("format_note") or "").lower()
    return any(marker in note for marker in ("original", "оригинал"))


def _is_original_audio(fmt):
    # YouTube may identify the original in the track note or in yt-dlp's
    # internal language preference (10 = original, 5 = default, -10 = audio description).
    if _has_original_marker(fmt):
        return True
    try:
        return float(fmt.get("language_preference")) >= 10
    except (TypeError, ValueError):
        return False


def _audio_language(fmt):
    return str(fmt.get("language") or fmt.get("language_code") or "").strip().lower()


def _is_russian_audio(fmt):
    language = _audio_language(fmt)
    note = str(fmt.get("format_note") or "").lower()
    language_match = (
        language == "ru" or language.startswith(("ru-", "ru_", "rus-"))
        or language in ("rus", "russian", "русский")
    )
    note_match = any(marker in note for marker in ("russian", "русск", "[ru]", "(ru)"))
    return language_match or (note_match and not any(marker in note for marker in ("english", "английск", "[en]", "(en)")))


def _is_youtube_short(url, info):
    parsed = urlparse(str(url))
    host = (parsed.hostname or "").lower().rstrip(".")
    if not (host == "youtu.be" or host == "youtube.com" or host.endswith(".youtube.com")):
        return False
    if parsed.path.lower().startswith("/shorts/"):
        return True
    dimensions = [
        (int(fmt.get("width") or 0), int(fmt.get("height") or 0))
        for fmt in (info.get("formats") or [])
        if fmt.get("vcodec") not in (None, "none")
    ]
    dimensions = [(width, height) for width, height in dimensions if width > 0 and height > 0]
    if not dimensions:
        return False
    width, height = max(dimensions, key=lambda item: item[0] * item[1])
    return height > width


def _available(info, is_short=False):
    formats = [f for f in (info.get("formats") or []) if f.get("url")]
    # Use the original audio for Shorts. For regular videos prefer an available
    # Russian track (original or dubbed), with the original track as fallback.
    video = [f for f in formats if f.get("ext") == "mp4"
             and str(f.get("vcodec") or "").startswith(("avc1", "h264"))
             and f.get("acodec") == "none"]
    audio = [f for f in formats if f.get("ext") in ("m4a", "mp4")
             and str(f.get("acodec") or "").startswith(("mp4a", "aac"))
             and f.get("vcodec") == "none"
             and (_is_original_audio(f) if is_short else _is_russian_audio(f))]
    if not audio and not is_short:
        audio = [f for f in formats if f.get("ext") in ("m4a", "mp4")
                 and str(f.get("acodec") or "").startswith(("mp4a", "aac"))
                 and f.get("vcodec") == "none" and _is_original_audio(f)]
    best_audio = max(audio, key=lambda f: f.get("abr") or f.get("tbr") or 0, default=None)
    choices = []
    if best_audio:
        track_label = ("оригинальная дорожка" if is_short else
                       ("русская озвучка" if _is_russian_audio(best_audio) else "исходная дорожка"))
        for f in video:
            height = f.get("height") or 0
            if height > 0:
                bitrate = round(f.get("tbr") or 0)
                choices.append({
                    "id": "merge:" + str(f["format_id"]) + "+" + str(best_audio["format_id"]),
                    "label": f"{height}p · MP4 со звуком · {track_label} · {f['format_id']}" +
                             (f" · {bitrate} кбит/с" if bitrate else ""),
                    "height": height, "kind": "merge",
                    "russianAudio": _is_russian_audio(best_audio),
                    "originalAudio": _is_original_audio(best_audio),
                })
    choices.sort(key=lambda item: item["height"], reverse=True)
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


def _inspect_best(url, cookie_file=None, use_cookies=False, js_runtime_path=None):
    best_info, best_choices, best_profile = None, [], "legacy"
    best_score = (-1, -1, -1)
    failures = []
    reports = []
    for profile in ("auto", "legacy", "alternate", "tv"):
        try:
            warnings = []
            opts = _options(profile, cookie_file, use_cookies, js_runtime_path)
            # Format discovery is metadata-only; short retries keep a slow
            # client profile from stalling the screen. Downloads keep their
            # normal retry policy.
            opts.update({"socket_timeout": 20, "retries": 1, "extractor_retries": 1})
            opts["logger"] = _DiagnosticLogger(warnings)
            with YoutubeDL(opts) as ydl:
                info = ydl.extract_info(url, download=False)
            is_short = _is_youtube_short(url, info)
            choices = _available(info, is_short=is_short)
            max_height = max((item["height"] for item in choices), default=0)
            preferred_key = "originalAudio" if is_short else "russianAudio"
            has_preferred_audio = any(item.get(preferred_key) for item in choices)
            reports.append({"profile": profile, "formats": len(info.get("formats") or []),
                            "max_video": max((fmt.get("height") or 0 for fmt in info.get("formats") or []
                                          if fmt.get("vcodec") not in (None, "none")), default=0),
                            "russian_audio": any(item.get("russianAudio") for item in choices),
                            "warnings": warnings[:8]})
            score = (int(has_preferred_audio), max_height, len(choices))
            if best_info is None or score > best_score:
                best_info, best_choices, best_profile = info, choices, profile
                best_score = score
            # Keep looking across YouTube clients until a Russian track is found;
            # Shorts retain their original track. Fall back to original audio if
            # none of the clients provides Russian audio.
            if max_height >= 720 and has_preferred_audio:
                break
        except Exception as exc:
            failures.append(str(exc)[:180])
            reports.append({"profile": profile, "error": str(exc)[:240]})
    if best_info is None:
        raise RuntimeError("Не удалось получить форматы: " + "; ".join(failures))
    return best_info, best_choices, best_profile, reports

def inspect_video(url, cookie_file=None, js_runtime_path=None):
    checked_url = _check_url(url)
    try:
        info, choices, profile, reports = _inspect_best(checked_url, js_runtime_path=js_runtime_path)
    except Exception as exc:
        if not cookie_file or not _needs_login(exc):
            raise
        # Use the account session only when YouTube explicitly asks for sign-in.
        info, choices, profile, reports = _inspect_best(checked_url, cookie_file, True, js_runtime_path)
    for choice in choices:
        choice["id"] = profile + "|" + choice["id"]
    raw_formats = info.get("formats") or []
    diagnostics = []
    for fmt in raw_formats:
        height = fmt.get("height") or 0
        if height or fmt.get("acodec") not in (None, "none"):
            diagnostics.append({
                "id": str(fmt.get("format_id") or "?"),
                "height": height,
                "ext": str(fmt.get("ext") or "?"),
                "video": str(fmt.get("vcodec") or "none"),
                "audio": str(fmt.get("acodec") or "none"),
                "language": str(fmt.get("language") or ""),
                "note": str(fmt.get("format_note") or ""),
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


def _fetch(url, directory, fmt_id, prefix, profile="legacy", cookie_file=None, use_cookies=False, js_runtime_path=None):
    def run(with_cookies):
        opts = _options(profile, cookie_file, with_cookies, js_runtime_path)
        opts["progress_hooks"] = [_progress_hook(directory, prefix)]
        opts.update({"format": fmt_id, "outtmpl": os.path.join(directory, prefix + ".%(ext)s"),
                     "overwrites": True, "restrictfilenames": True})
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
            return info, _get_file(info, ydl)

    try:
        return run(use_cookies)
    except Exception as exc:
        if use_cookies or not cookie_file or not _needs_login(exc):
            raise
        return run(True)

def download_video(url, directory, choice, cookie_file=None, js_runtime_path=None):
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
    use_cookies = False
    try:
        with YoutubeDL(_options(profile, js_runtime_path=js_runtime_path)) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as exc:
        if not cookie_file or not _needs_login(exc):
            raise
        use_cookies = True
        with YoutubeDL(_options(profile, cookie_file, True, js_runtime_path)) as ydl:
            info = ydl.extract_info(url, download=False)
    valid = {item["id"] for item in _available(info, is_short=_is_youtube_short(url, info))}
    if selection not in valid:
        raise ValueError("Формат изменился. Обнови список форматов.")
    kind, fmt = selection.split(":", 1)
    paths = []
    if kind == "merge":
        video_id, audio_id = fmt.split("+", 1)
        _, video = _fetch(url, directory, video_id, "video", profile, cookie_file, use_cookies, js_runtime_path)
        _, audio = _fetch(url, directory, audio_id, "audio", profile, cookie_file, use_cookies, js_runtime_path)
        paths = [video, audio]
    elif kind == "audio":
        _, audio = _fetch(url, directory, fmt, "audio", profile, cookie_file, use_cookies, js_runtime_path)
        paths = ["", audio]
    else:
        _, video = _fetch(url, directory, fmt, "video", profile, cookie_file, use_cookies, js_runtime_path)
        paths = [video, ""]
    return json.dumps({
        "video": paths[0], "audio": paths[1], "merge": kind == "merge",
        "title": info.get("title") or info.get("id") or "Видео",
        "channel": info.get("channel") or info.get("uploader") or "YouTube",
        "date": info.get("upload_date") or "",
        "description": info.get("description") or "",
        "chapters": [{"start_time": chapter.get("start_time"), "title": chapter.get("title") or ""}
                     for chapter in (info.get("chapters") or [])],
    }, ensure_ascii=False)

def _published_date(info):
    """Format the best available YouTube publication date as DD.MM.YYYY."""
    for key in ("upload_date", "timestamp", "release_date", "release_timestamp"):
        value = info.get(key)
        if value in (None, ""):
            continue
        text = str(value).strip()
        if len(text) == 8 and text.isdigit():
            return f"{text[6:8]}.{text[4:6]}.{text[:4]}"
        if len(text) >= 10 and text[4:5] == "-" and text[7:8] == "-":
            try:
                parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
                if parsed.tzinfo is None:
                    parsed = parsed.replace(tzinfo=timezone.utc)
                return parsed.astimezone(timezone.utc).strftime("%d.%m.%Y")
            except ValueError:
                pass
        try:
            timestamp = float(value)
            if timestamp > 100_000_000_000:
                timestamp /= 1000.0
            return datetime.fromtimestamp(timestamp, tz=timezone.utc).strftime("%d.%m.%Y")
        except (TypeError, ValueError, OverflowError, OSError):
            continue
    return ""


def latest_channel_videos(channel_url, cookie_file=None, js_runtime_path=None):
    url = _check_url(channel_url).rstrip("/")
    if "/@" not in url and "/channel/" not in url and "/c/" not in url and "/user/" not in url:
        raise ValueError("Нужна ссылка на YouTube-канал")
    if not url.endswith("/videos"):
        url += "/videos"
    def load_channel(use_cookies=False):
        opts = _options(cookie_file=cookie_file, use_cookies=use_cookies, js_runtime_path=js_runtime_path)
        opts.update({"extract_flat": "in_playlist", "playlistend": 2, "ignoreerrors": True})
        with YoutubeDL(opts) as ydl:
            return ydl.extract_info(url, download=False)

    try:
        info = load_channel()
    except Exception as exc:
        if not cookie_file or not _needs_login(exc):
            raise
        info = load_channel(use_cookies=True)
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
            title = item.get("title") or "Видео"
            published_at = _published_date(item)
            # Flat channel entries may omit dates and localized titles.
            # Resolve each video individually, then accept yt-dlp's upload
            # timestamp/date fields when upload_date is absent.
            try:
                try:
                    with YoutubeDL(_options(js_runtime_path=js_runtime_path)) as detail_ydl:
                        detail = detail_ydl.extract_info(link, download=False)
                except Exception as exc:
                    if not cookie_file or not _needs_login(exc):
                        raise
                    with YoutubeDL(_options(cookie_file=cookie_file, use_cookies=True, js_runtime_path=js_runtime_path)) as detail_ydl:
                        detail = detail_ydl.extract_info(link, download=False)
                if detail:
                    title = detail.get("title") or title
                    published_at = _published_date(detail) or published_at
            except Exception:
                pass
            result.append({"title": title, "url": link, "published_at": published_at})
        if len(result) == 2:
            break
    return json.dumps(result, ensure_ascii=False)


def inspect_playlist(playlist_url, limit=100, cookie_file=None, js_runtime_path=None):
    """Read playlist entries without downloading media; cap expansion for mobile."""
    url = _check_url(playlist_url)
    parsed = urlparse(url)
    from urllib.parse import parse_qs
    if not parse_qs(parsed.query).get("list") and "/playlist" not in parsed.path:
        raise ValueError("Нужна ссылка YouTube-плейлиста с параметром list")
    limit = max(1, min(int(limit), 100))
    # Playlist metadata should fail quickly on a broken VPN/DNS connection.
    # Video downloads retain their separate retry policy.
    def load_playlist(use_cookies=False):
        opts = _options("auto", cookie_file, use_cookies, js_runtime_path)
        opts.update({"noplaylist": False, "extract_flat": "in_playlist",
                     "playlistend": limit, "ignoreerrors": True,
                     "socket_timeout": 12, "retries": 1,
                     "extractor_retries": 1, "fragment_retries": 1})
        with YoutubeDL(opts) as ydl:
            return ydl.extract_info(url, download=False)

    try:
        info = load_playlist()
    except Exception as exc:
        if not cookie_file or not _needs_login(exc):
            raise
        info = load_playlist(use_cookies=True)
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
