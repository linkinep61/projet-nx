package com.streamflixreborn.streamflix.fragments.extractor_stats

/**
 * Onglet "Extracteurs" version TV — pour l'instant, on hérite du fragment
 * Mobile : c'est un écran simple (RecyclerView + 2 boutons) qui marche
 * en navigation D-pad sans modification. Si l'UI Leanback s'avère
 * nécessaire plus tard (focus visuel TNT), on subclassera ou on créera
 * un layout dédié `fragment_extractor_stats_tv.xml`.
 */
class ExtractorStatsTvFragment : ExtractorStatsMobileFragment()
