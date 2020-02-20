# Freechains: Let's redistribute the Internet!

Freechains is a decentralized topic-based publish-subscribe system.

A peer publishes a message to a topic (a chain) and all other connected peers
interested in the same topic eventually receive the message.

## Goals

The system should be decentralized, fair, free (*as-in-speech*), free 
(*as-in-beer*), privacy aware, secure, persistent, SPAM resistant, and 
scalable:

1. The system **should not be** controlled by an authority (or a minority).
2. Users **should be** equally able to publish content.
3. Publishing **should not be** censorable.
4. Publishing and reading **should be** free of charge (as much as possible).
5. Publications **should be** hideable from unwanted users.
6. Publications **should be** verifiable and **should not be** modifiable.
7. Publications **should be** permanently available.
8. The system **should be** resistant to SPAM.
9. The system **should be** scalable to the size of the Internet.

## Install

TODO

## Use

### Command Line

- Create a `freechains` host:

```
$ freechains host create /tmp/myhost
```

- Join the `/chat` chain:

```
$ freechains chain join /chat
```

- Publish some content:

```
$ freechains chain put /chat inline utf8 Hello_World
```

- Communicate with other peers:

TODO

<!--
```
# Setup configuration files:
$ cp cfg/config.lua.bak /tmp/config-8331.lua
$ cp cfg/config.lua.bak /tmp/config-8332.lua

# Start two new nodes:
$ freechains --port=8331 daemon start /tmp/config-8331.lua &
$ freechains --port=8332 daemon start /tmp/config-8332.lua &

# Connect, in both directions, 8330 with 8331 and 8331 with 8332:
$ freechains --port=8330 configure set "chains[''].peers"+="{address='127.0.0.1',port=8331}"
$ freechains --port=8331 configure set "chains[''].peers"+="{address='127.0.0.1',port=8330}"
$ freechains --port=8331 configure set "chains[''].peers"+="{address='127.0.0.1',port=8332}"
$ freechains --port=8332 configure set "chains[''].peers"+="{address='127.0.0.1',port=8331}"

$ freechains --port=8332 publish /0 +"Hello World (from 8332)"
```

This creates a peer-to-peer mesh with the form `8330 <-> 8331 <-> 8332`,
allowing nodes `8330` and `8332` to communicate even though they are not
directly connected.
-->
