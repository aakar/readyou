package me.ash.reader.infrastructure.exception

class FeedlyTokenExpiredException : FeedlyAPIException("Access token expired — please re-authenticate")
