name: Hypixel2020
version: 1.0.0
main: net.h2020.H2020Plugin
author: h2020
description: Private Hypixel 2020 recreation (Bed Wars, SkyWars, human-like bots)
load: POSTWORLD
commands:
  play:
    description: Join a game, e.g. /play bedwars_eight_one
    usage: /play <game>
  lobby:
    description: Return to lobby
    aliases: [l, hub]
  h2020:
    description: Admin/video tools (bots, games)
    permission: h2020.admin
  bot:
    description: Bot tools (spawn, cheat profiles, list) - owned by bots module
    permission: h2020.admin
  shout:
    description: Bed Wars shout chat
permissions:
  h2020.admin:
    description: Admin and video tools
    default: op
