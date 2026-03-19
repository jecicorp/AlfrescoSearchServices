# About

This folder contains code for provisioning ACS with SearchServices - for testing purposes.

# Content

```ruby
.
├── README.md
└── search                              # SEARCH SERVICES
    ├── Makefile                        # main Makefile - inherited by all Makefiles bellow
    ├── backup
    │   ├── Makefile
    │   ├── README.md                   # start here if you want to test the backup
    │   └── docker-compose.backup.yml
    ├── custom
    │   ├── Dockerfile
    │   ├── Makefile
    │   ├── README.md                   # start here if you want to built/test a custom image
    │   ├── docker-compose.custom.yml
    │   └── spellcheck
    │       └── enable-spellcheck.sh
    ├── docker-compose.yml
    ├── sharding
    │   ├── Makefile
    │   ├── README.md
    │   └── docker-compose.sharding.yml
    └── upgrade
        ├── Makefile
        ├── README.md
        └── docker-compose.upgrade.yml
```
