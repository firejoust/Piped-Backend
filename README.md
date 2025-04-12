# How to Use

This is a temporary hack to fix new videos not appearing on the feed.

1. Follow self-hosting instructions [here](https://docs.piped.video/docs/self-hosting/) up to `cd Piped-Docker`.
2. Inside `Piped-Docker/template`, modify the relevant template with the following:
```dockerfile
    piped: # This is the backend service
        # image: 1337kavin/piped:latest # Removed this line
        build: # Build the image with the workaround
            context: https://github.com/firejoust/Piped-Backend.git # Tells docker-compose where to get the source
            dockerfile: Dockerfile # Specifies the Dockerfile within the context
```
3. Append the following to `Piped-Docker/config/config.properties`:
```sh
# Enable feed polling workaround
ENABLE_FEED_POLLING=true
POLLING_INTERVAL_MINUTES=15
POLLING_FETCH_LIMIT_PER_CHANNEL=10
```
4. After running `./configure-instance.sh` and selecting the relevant template, run the following to start the container:
```sh
sudo DOCKER_BUILDKIT=1 docker-compose up -d
```

# README.md:

```markdown
# Piped-Backend

An advanced open-source privacy friendly alternative to YouTube, crafted with the help of [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor).

## Official Frontend

-   VueJS frontend - [Piped](https://github.com/TeamPiped/Piped)

## Community Projects

-   See https://github.com/TeamPiped/Piped#made-with-piped
```