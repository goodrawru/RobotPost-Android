import json
import os
import re
from yt_dlp import YoutubeDL

def _check_url(url):
    from urllib.parse import urlparse
    parsed = urlparse(url.strip())
    host = (parsed.hostname or "").lower()
    if parsed.scheme != "https" or host not in ("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "music.youtube.com"):
        raise ValueError("Нужна HTTPS-ссылка YouTube")
    return url.strip()

def _options():
    return {
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "retries": 3,
        "fragment_retries": 3,
        "socket_timeout": 30,
        "extractor_args": {"youtube": {"player_client": ["android", "web"]}},
    }

def inspect_video(url):
    url = _check_url(url)
    with YoutubeDL(_options()) as ydl:
        info = ydl.extract_info(url, download=False)
    if not info:
        raise RuntimeError("Не удалось получить сведения о видео")
    choices = []
    for fmt in info.get("formats") or []:
        if fmt.get("vcodec") in (None, "none") or fmt.get("acodec") in (None, "none"):
            continue
        if fmt.get("ext") != "mp4":
            continue
        fmt_id = str(fmt.get("format_id") or "")
        if not fmt_id or not fmt.get("url"):
            continue
        height = fmt.get("height")
        label = (str(height) + "p" if height else "MP4") + " · MP4 со звуком"
        size = fmt.get("filesize") or fmt.get("filesize_approx")
        if size:
            label += " · " + str(round(size / 1048576)) + " МБ"
        choices.append({"id": fmt_id, "label": label, "height": height or 0})
    choices.sort(key=lambda item: item["height"], reverse=True)
    return json.dumps({
        "title": info.get("title") or "Видео",
        "channel": info.get("channel") or info.get("uploader") or "YouTube",
        "formats": choices,
        "has_separate_streams": any(f.get("vcodec") not in (None, "none") and f.get("acodec") == "none" for f in info.get("formats") or []),
    }, ensure_ascii=False)

def download_video(url, directory, format_id=""):
    url = _check_url(url)
    os.makedirs(directory, exist_ok=True)
    options = _options()
    options.update({
        "format": str(format_id) if format_id else "best[ext=mp4][vcodec!=none][acodec!=none]",
        "outtmpl": os.path.join(directory, "%(id)s.%(ext)s"),
        "restrictfilenames": True,
        "overwrites": False,
    })
    with YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=True)
        if not info:
            raise RuntimeError("Не удалось получить информацию о видео")
        file_path = None
        for entry in info.get("requested_downloads", []):
            candidate = entry.get("filepath")
            if candidate and os.path.isfile(candidate):
                file_path = candidate
                break
        if not file_path:
            candidate = ydl.prepare_filename(info)
            if os.path.isfile(candidate):
                file_path = candidate
        if not file_path:
            raise RuntimeError("Скачанный файл не найден")
        if not file_path.lower().endswith(".mp4"):
            raise RuntimeError("Выбранный поток не является MP4")
        return json.dumps({
            "path": file_path,
            "title": info.get("title") or info.get("id") or "Видео",
            "channel": info.get("channel") or info.get("uploader") or "YouTube",
            "date": info.get("upload_date") or "",
            "description": info.get("description") or "",
        }, ensure_ascii=False)
