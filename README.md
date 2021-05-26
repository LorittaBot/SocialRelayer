
<h1 align="center">🐦 SocialRelayer 🐦</h1>

SocialRelayer is a social media relayer to Discord via webhooks, pulling Loritta's guild configurations to automatically register and synchronize new posts from other platforms to Discord.

SocialRelayer is not really a "micro-service", from the words of [Napster](https://github.com/napstr/)]...

> if your "microservices" are accessing the same tables, you dont have microservices, you have a distributed monolith

This is intentional to *Keep It Simple, Stupid*! 😛 Currently we don't need to receive YouTube/Twitch/Twitter/etc notifications from other projects that aren't Loritta, however if some day we *need* to do this, refactoring SocialRelayer to support this shouldn't be that hard.

## Synchronization 

While this repository is targeted only to Loritta, the repository still contains some nifty tricks to synchronize social media data to Discord, which they may be useful to other developers, here's a high level overview of the tips and tricks we implemented.

### Twitter

Twitter tweet synchronization uses a multitude of different methods to track all tweets from all tracked accounts, falling back when a method is in its limits.

* [Twitter API v1 Stream](https://developer.twitter.com/en/docs/twitter-api/v1/tweets/filter-realtime/overview) (Deprecated, but still works!)
* [Twitter API v2 Stream](https://developer.twitter.com/en/docs/twitter-api/tweets/filtered-stream/quick-start)
* [Twitter Timeline Polling](https://publish.twitter.com/)

To track everyone...

* The top 10000 accounts are tracked via Twitter API v1 Stream, Twitter API v1 Stream is the best because you can register 5000 users per stream (you can have two streams per IP) and there aren't any tweet limits!
* The ~200 accounts are tracked via Twitter API v2 Stream, Twitter API v2 Stream is worse than the Twitter API v1 Stream and you can't even get *near* the limit Twitter API v1 Stream provides, Twitter API v2 Stream uses Twitter Search Rules (`user:LorittaBot OR user:MrPowerGamerBR`...) so your tracked account limit is based on the length of the usernames you want to track. You can register 25 rules and all rules needs to have less than 512 characters. Twitter API v2 Stream also consumes your monthly quota of tweets,
* The remaining accounts are tracked via polling Twitter's Timeline via the Publish embed. There doesn't seem to have any limits in the Publish embed and because it is hosted in a `cdn` domain the chances of you getting IP banned seems to be very low. However polling is a gigantic cumbersome task that uses a lot of resources, so Loritta periodically checks the timelines depending on the user's activity.
* * **User tweeted in the last 3 days?** Check every 30 seconds!
* * **User tweeted in the last 7 days?** Check every 120 seconds!
* * **User tweeted in the last 14 days?** Check every 5 minutes!
* * **User made their account private (or there was a polling error)?** Check every 24 hours!
* * **Anything else?** Check every 15 minutes!
* * The requests also have a bit of randomness in them to avoid querying everything all at once, and to make the requests seem less [sus](https://www.youtube.com/watch?v=QYswdRMsAoU&feature=youtu.be&t=715).

There is also some other polling methods that could be done if Twitter starts limiting its API...

* Add all tracked users to a list, however you may get list shadow-banned for a few days. Twitter allows you to query all the tweets in a single list, making it perfect as a polling alternative and it uses way less requests than polling everyone manually!

### YouTube

*TODO*

### Twitch

*TODO*