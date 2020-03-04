# Freechains: Let's redistribute the Internet!

Freechains is a decentralized topic-based publish-subscribe system.

A peer posts a message to a topic (a chain) and all other connected peers
interested in the same topic eventually receive the message.

## Goals

The system should be decentralized, fair, free (*as-in-speech*), free 
(*as-in-beer*), privacy aware, secure, persistent, SPAM resistant, and 
scalable:

1. The system **should not be** controlled by an authority (or a minority).
2. Users **should be** equally able to publish content.
3. Posting **should not be** censorable.
4. Posting and reading **should be** free of charge (as much as possible).
5. Posting **should be** hideable from unwanted users.
6. Posting **should be** verifiable and **should not be** modifiable.
7. Posting **should be** permanently available.
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

- Start host:

```
$ freechains host start /tmp/myhost &
```

- Join the `/chat` chain:

```
$ freechains chain join /chat
```

- Post some content:

```
$ freechains chain post /chat inline utf8 "Hello World!"
$ freechains chain post /chat inline utf8 "I am here!"
```

- Communicate with other peers:
   - Create another `freechains` host.
   - Start new host.
   - Join the `/chat` chain.
   - Synchronize from the first host.

```
$ freechains host create /tmp/othost 8331
$ freechains host start /tmp/othost &
$ freechains --host=localhost:8331 chain join /chat
$ freechains --host=localhost:8330 chain send /chat localhost:8331
```

The last command sends all new posts from `8330` to `8331`, which can
then be traversed as follows:
    - Identify the predefined "genesis" post of `/chat`.
    - Acquire it to see what comes next.
    - Iterate over its `fronts` posts recursively.

```
$ freechains chain genesis /chat
0_A80B5390F7CF66A8781F42AEB68912F2745FC026A71885D7A3CB70AB81764FB2
$ freechains chain get /chat 0_A80B5390F7CF66A8781F42AEB68912F2745FC026A71885D7A3CB70AB81764FB2
{
    ...
    "fronts": [
        "1_1D5D2B146B49AF22F7E738778F08E678D48C6DAAF84AF4128A17D058B6F0D852"
    ],
    ...
}
$ freechains chain get /chat 1_1D5D2B146B49AF22F7E738778F08E678D48C6DAAF84AF4128A17D058B6F0D852
{
    "immut": {
        ...
        "payload": "Hello World!",
        "backs": [
            "0_A80B5390F7CF66A8781F42AEB68912F2745FC026A71885D7A3CB70AB81764FB2"
        ]
    },
    "fronts": [
        "2_DFDC784B4609F16F4487163CAC531A9FE6A0C588DA39D597769DA279AB53C862"
    ],
    ...
}
```
