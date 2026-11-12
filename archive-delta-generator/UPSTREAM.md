# Archive Patcher attribution

Source: https://github.com/andrewhayden/archive-patcher
Baseline: `50ca40a3de7983392a383ed3cc7b48e25f1b69b3` (2019-03-26).

`src/main/java/com/google/archivepatcher/generator` is copied from upstream. Original copyright and license headers are retained. Modified files carry an additional SlayTheAmethyst notice. DivSuffixSorter.java retains its MIT notice. Complete upstream Apache-2.0 and MIT texts ship in LICENSE.archive-patcher and the JAR.

The project facade uses explicit working directories and budgeted heap-backed BSDiff, not legacy mmap/default-temp entry points. This is a derivative, not a Google-endorsed release.
