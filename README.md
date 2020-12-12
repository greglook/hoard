Hoard
=====

Hoard is a command-line tool for incrementally managing secure, encrypted
backups of your important files.


## Design

git-like index into a content-addressed store of files, but all the objects are encrypted

```
repository
├── meta.edn
├── archive
│   ├── foo
│   │   ├── 20201204-01482-abcd
│   │   ├── 20201210-57391-defg
│   │   └── ...
│   └── bar
│       └── ...
└── data
    ├── meta.properties
    └── blocks
        ├── 11140000
        │   ├── debc06fba391088613aafb041a23f0cb8f5ceaad9b487e2928897a75933778
        │   ├── b2c7eef7421670bd4aca894ed27a94c8219e181d7b63006bea3038240164c1
        │   └── ...
        ├── 11140001
        │   └── ...
        └── ...
```


## Operations

### List Archives

List the archive namespaces present in the repository.
- optionally include metadata

### Inspect Archive

View information about an archive including metadata, storage statistics, and
available versions.

### Archive Data

creating a backup:
- iterating over all of the files on disk
- computing a hash of each file
- if the hash is not present on the other end, encrypt and send the file
- generate an index of all files with hash/mtime/size/path
- encrypt and store the timestamped index

### Restore Data

restoring:
- selecting index to restore from and a location to restore to
    - could accept prefix to scope restored files to
- pull and decrypt index
- compare index data to files on disk
- for each missing (or non-matching) file, pull, decrypt, and place file
    - probably want to prompt to overwrite or --force

### Verify Archive

- verify index by checking that all files are present in target storage
- deep verify would decrypt and re-hash files

### Prune Data

- prune data outside a list of indexes to keep

### Statistics

- print stats about index and total backup repo size
