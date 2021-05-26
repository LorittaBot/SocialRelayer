
<h1 align="center">🐦 SocialRelayer 🐦</h1>

SocialRelayer is a social media relayer to Discord via webhooks, pulling Loritta's guild configurations to automatically register and synchronize new posts from other platforms to Discord.

SocialRelayer is not really a "micro-service", from the words of [Napster](https://github.com/napstr/): "if your "microservices" are accessing the same tables, you dont have microservices, you have a distributed monolith". This is intentional to *Keep It Simple, Stupid*! 😛 Also because currently we don't need to receive YouTube/Twitch/Twitter/etc notifications from other projects that aren't Loritta, however if some day we *need* to do this, refactoring SocialRelayer to support this shouldn't be that hard.