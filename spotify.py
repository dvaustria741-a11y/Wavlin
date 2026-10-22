import os
import re
import base64
import requests
from typing import Optional, List, Dict, Any

class SpotifyProvider:
    """
    Spotify metadata provider for Wavlin. Resolves tracks, albums, and playlists 
    into standard search queries compatible with your YouTube backend provider.
    """
    
    API_AUTH_URL = "https://accounts.spotify.com/api/token"
    API_BASE_URL = "https://api.spotify.com/v1"
    
    # Regex patterns for Spotify resource routing
    TRACK_REGEX = re.compile(r"spotify\.com/track/([a-zA-Z0-9]+)")
    PLAYLIST_REGEX = re.compile(r"spotify\.com/playlist/([a-zA-Z0-9]+)")
    ALBUM_REGEX = re.compile(r"spotify\.com/album/([a-zA-Z0-9]+)")

    def __init__(
        self, 
        client_id: Optional[str] = None, 
        client_secret: Optional[str] = None
    ):
        self.client_id = client_id or os.environ.get("SPOTIFY_CLIENT_ID", "")
        self.client_secret = client_secret or os.environ.get("SPOTIFY_CLIENT_SECRET", "")
        self._access_token: Optional[str] = None

    def _authenticate(self) -> None:
        """Requests a fresh OAuth bearer token using Client Credentials flow."""
        if not self.client_id or not self.client_secret:
            raise RuntimeError("Set SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET (or pass them to SpotifyProvider).")
        auth_string = f"{self.client_id}:{self.client_secret}"
        encoded_auth = base64.b64encode(auth_string.encode("utf-8")).decode("utf-8")
        
        headers = {
            "Authorization": f"Basic {encoded_auth}",
            "Content-Type": "application/x-www-form-urlencoded"
        }
        data = {"grant_type": "client_credentials"}
        
        response = requests.post(self.API_AUTH_URL, headers=headers, data=data, timeout=10)
        response.raise_for_status()
        
        token_data = response.json()
        self._access_token = token_data.get("access_token")

    def _get_headers(self) -> Dict[str, str]:
        if not self._access_token:
            self._authenticate()
        return {"Authorization": f"Bearer {self._access_token}"}

    def get_track_metadata(self, track_id: str) -> Dict[str, Any]:
        """Fetches track metadata and constructs a YouTube-compatible search query."""
        url = f"{self.API_BASE_URL}/tracks/{track_id}"
        response = requests.get(url, headers=self._get_headers(), timeout=10)
        
        if response.status_code == 401:  # Token expired
            self._authenticate()
            response = requests.get(url, headers=self._get_headers(), timeout=10)
            
        response.raise_for_status()
        data = response.json()
        
        title = data.get("name")
        artists = ", ".join([artist["name"] for artist in data.get("artists", [])])
        duration_ms = data.get("duration_ms", 0)
        
        return {
            "title": title,
            "artist": artists,
            "query": f"{artists} - {title}",
            "duration": duration_ms // 1000,
            "url": data.get("external_urls", {}).get("spotify")
        }

    def get_playlist_tracks(self, playlist_id: str) -> List[Dict[str, Any]]:
        """Pulls all tracks from a public Spotify playlist with pagination support."""
        url = f"{self.API_BASE_URL}/playlists/{playlist_id}/tracks"
        tracks = []
        
        while url:
            response = requests.get(url, headers=self._get_headers(), timeout=10)
            if response.status_code == 401:
                self._authenticate()
                response = requests.get(url, headers=self._get_headers(), timeout=10)
                
            response.raise_for_status()
            data = response.json()
            
            for item in data.get("items", []):
                track = item.get("track")
                if track and track.get("name"):
                    title = track["name"]
                    artists = ", ".join([artist["name"] for artist in track.get("artists", [])])
                    tracks.append({
                        "title": title,
                        "artist": artists,
                        "query": f"{artists} - {title}",
                        "duration": track.get("duration_ms", 0) // 1000,
                        "url": track.get("external_urls", {}).get("spotify")
                    })
            
            url = data.get("next")
            
        return tracks

    def resolve(self, url: str) -> Any:
        """Routes incoming Spotify URLs to the correct metadata extractor."""
        if match := self.TRACK_REGEX.search(url):
            return self.get_track_metadata(match.group(1))
        elif match := self.PLAYLIST_REGEX.search(url):
            return self.get_playlist_tracks(match.group(1))
        raise ValueError(f"Unsupported or invalid Spotify URL: {url}")
