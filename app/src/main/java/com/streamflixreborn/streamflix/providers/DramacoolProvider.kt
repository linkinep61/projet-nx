package com.streamflixreborn.streamflix.providers

// 2026-05-04 : Dramacool n'est pas un provider standalone — il est utilisé
// comme SOURCE SUPPLÉMENTAIRE dans VoirDramaProvider pour étendre le
// catalogue (search croisé) et fournir des players additionnels en fallback.
// Voir VoirDramaProvider.searchOnDramacool() / fetchDramacoolServers().
