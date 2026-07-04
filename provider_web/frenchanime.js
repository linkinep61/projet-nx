/* frenchanime.js — French Anime (french-anime.com) en WebJS. CF via WebView.
 * 2026-07-03 REFONTE (user) : plus de split VF/VOSTFR au catalogue. Deux onglets
 *   SIMPLES = Séries (getTvShows) et Films (getMovies), chacun avec le FRANÇAIS EN
 *   TÊTE puis le VOSTFR à la suite (dispo quand même). Fiche = pas de dossier
 *   "FRENCH" redondant : une saison "Saison N" qui mène direct aux épisodes.
 *   Serveurs TAGGÉS par la langue de la fiche (champ "Version") → le player trie
 *   VF/VOSTFR correctement. getVideo = Extractor.extract côté app.
 */
(function () {
  var BASE = location.origin;
  var GENRES = [['action','Action'],['aventure','Aventure'],['comedie','Comedie'],['fantasy','Fantasy'],['shonen','Shonen'],['romance','Romance'],['horreur','Horreur'],['sci-fi','Sci-Fi']];
  function abs(u){ if(!u) return u; if(u.indexOf('http')===0) return u; return BASE+(u.charAt(0)==='/'?'':'/')+u; }
  function relId(href){ try{ return new URL(href,BASE).pathname.replace(/^\//,''); }catch(e){ return href; } }
  function clean(s){ return (s||'').replace(/voir la suite\.*/i,'').replace(/\s+/g,' ').trim(); }
  function imgUrl(img){ return img?(img.getAttribute('src')||img.getAttribute('data-src')||img.getAttribute('data-lazy-src')||img.getAttribute('data-original')):''; }
  function movVF(m){ var s=m.querySelector('.block-sai')||m.querySelector('.nbloc1'); return /french/i.test(s?s.textContent:''); }
  function parseMov(m){
    var a=m.querySelector('a.mov-t'); if(!a) return null;
    var href=a.getAttribute('href'); if(!href) return null;
    return { type:m.querySelector('.block-ep')?'tv':'movie', id:relId(href), title:a.textContent.trim(), poster:abs(imgUrl(m.querySelector('.mov-i img'))) };
  }
  function parseList(root){ return [].slice.call((root||document).querySelectorAll('div.mov')).map(parseMov).filter(Boolean); }
  function parseOwl(doc){
    return [].slice.call((doc||document).querySelectorAll('.owl-carousel .item')).map(function(it){
      var a=it.querySelector('a'); if(!a) return null; var href=a.getAttribute('href'); if(!href) return null;
      var t=(it.querySelector('.mov-t')||a).textContent;
      return { type:it.querySelector('.block-ep')?'tv':'movie', id:relId(href), title:clean(t), poster:abs(imgUrl(it.querySelector('img'))) };
    }).filter(Boolean);
  }
  async function fetchDoc(path){
    var url = path.indexOf('http')===0 ? path : (BASE+'/'+path.replace(/^\//,''));
    var r=await fetch(url,{credentials:'include'});
    return new DOMParser().parseFromString(await r.text(),'text/html');
  }
  function isChallenge(doc){ return /just a moment|un instant|verification|checking your browser/i.test((doc&&doc.title)||''); }
  function detailBasics(doc){ doc=doc||document;
    var h1=doc.querySelector('h1[itemprop=name]');
    var pimg=doc.querySelector('div.mov-img img[itemprop=thumbnailUrl]')||doc.querySelector('.mov-img img');
    var d=doc.querySelector('[itemprop=description]')||doc.querySelector('div.mov-desc');
    return { title:h1?h1.textContent.trim():'', poster:abs(imgUrl(pimg)), overview:d?d.textContent.trim():'' };
  }
  // Langue de la fiche depuis le champ "Version" (FRENCH / VOSTFR). Sert au tag serveurs.
  function pageVersionLabel(doc){ doc=doc||document;
    var li=[].slice.call(doc.querySelectorAll('ul.mov-list li')).filter(function(l){var lab=l.querySelector('.mov-label');return lab&&/version/i.test(lab.textContent);})[0];
    var t=li?li.textContent.replace(/version/i,'').replace(/[:]/g,'').trim():'';
    if(/vostfr/i.test(t)) return 'VOSTFR';
    if(/french|(^|[^a-z])vf([^a-z]|$)/i.test(t)) return 'VF';
    return '';
  }
  function parseEps(doc){ doc=doc||document;
    var eps=doc.querySelector('div.eps'); if(!eps) return [];
    var t=eps.textContent.trim(); if(!t) return [];
    return t.split(/\s+/).map(function(tok){
      var p=tok.split('!'); if(p.length!==2) return null;
      var num=parseInt(p[0],10); if(isNaN(num)) return null;
      var urls=p[1].split(',').filter(function(u){return u&&u.indexOf('http')===0;});
      return {num:num, urls:urls};
    }).filter(Boolean);
  }
  function delay(ms){ return new Promise(function(r){ setTimeout(r, ms); }); }
  async function getHome(){
    var doc=document;
    try{ var d=await fetchDoc(''); if(d.querySelectorAll('.block-main').length||d.querySelectorAll('div.mov').length||d.querySelectorAll('.owl-carousel .item').length) doc=d; }catch(e){}
    var cats=[]; var seen={};
    function mark(items){ for(var i=0;i<items.length;i++){ if(items[i].id) seen[items[i].id]=1; } }
    var feat=parseOwl(doc); if(feat.length){ mark(feat); cats.push({ name:'', items:feat }); }
    [].slice.call(doc.querySelectorAll('.block-main')).forEach(function(b){
      var t=b.querySelector('.block-title,h2,.bmt'); var items=parseList(b);
      if(items.length){ mark(items); cats.push({ name:clean(t?t.textContent:'')||'Animes', items:items }); }
    });
    if(!cats.length){ var all=parseList(doc); if(all.length) cats=[{name:'Nouveautes',items:all}]; }
    return cats;
  }
  async function search(query){
    if(!query){
      var doc=document; if(!doc.querySelector('div.side-b nav ul li a')){ try{ doc=await fetchDoc(''); }catch(e){} }
      return [].slice.call(doc.querySelectorAll('div.side-b nav ul li a')).map(function(a){
        var href=a.getAttribute('href')||''; var s=href.split('/genre/')[1]; if(s) s=s.replace(/\/.*$/,'');
        if(!s) return null; return { type:'genre', id:s, title:a.textContent.trim() };
      }).filter(Boolean);
    }
    return parseList(await fetchDoc('index.php?do=search&subaction=search&story='+encodeURIComponent(query)));
  }
  async function getGenre(id,page){ page=page||1; return parseList(await fetchDoc('genre/'+id+'/page/'+page)); }
  // FILMS (onglet Films) : tous les films, FRANÇAIS EN TÊTE puis VOSTFR.
  async function getMovies(page){ page=page||1; var vf=[], vo=[];
    try{ var d=await fetchDoc('films-vf-vostfr/page/'+page);
      [].slice.call(d.querySelectorAll('div.mov')).forEach(function(m){ var it=parseMov(m); if(!it) return; it.type='movie'; it.isSeries=false; (movVF(m)?vf:vo).push(it); });
    }catch(e){}
    return vf.concat(vo);
  }
  // SÉRIES (onglet Séries) : toutes les séries, FRANÇAIS EN TÊTE (animes-vf) puis VOSTFR (animes-vostfr).
  async function getTvShows(page){ page=page||1; var out=[];
    try{ var dvf=await fetchDoc('animes-vf/page/'+page+'/');
      [].slice.call(dvf.querySelectorAll('div.mov')).forEach(function(m){ var it=parseMov(m); if(it){ it.type='tv'; it.isMovie=false; out.push(it); } });
    }catch(e){}
    try{ var dvo=await fetchDoc('animes-vostfr/page/'+page+'/');
      [].slice.call(dvo.querySelectorAll('div.mov')).forEach(function(m){ var it=parseMov(m); if(it){ it.type='tv'; it.isMovie=false; out.push(it); } });
    }catch(e){}
    return out;
  }
  async function getMovie(id){ var b=detailBasics(document); return { id:id, type:'movie', title:b.title, poster:b.poster, overview:b.overview }; }
  async function getTvShow(id,lang){
    var b=detailBasics(document);
    var sNum=parseInt((b.title.match(/[Ss]aison\s*(\d+)/)||[])[1]||'1',10)||1;
    // Une saison NEUTRE "Saison N" (plus de dossier "FRENCH"/"VOSTFR" redondant).
    return { id:id, type:'tv', title:b.title, poster:b.poster, overview:b.overview,
      seasons:[ { id:id+'#s', number:sNum, title:'Saison '+sNum, poster:b.poster } ] };
  }
  async function getEpisodesBySeason(seasonId,lang){
    var showPath=seasonId.split('#')[0];
    var b=detailBasics(document);
    return parseEps(document).map(function(e){ return { id:showPath+'#ep'+e.num, number:e.num, title:'Épisode '+e.num, poster:b.poster }; });
  }
  async function extractServers(lang){
    var eps=parseEps(document); if(!eps.length) return [];
    var ver=pageVersionLabel(document); // "VF" / "VOSTFR" / ""
    var m=(location.hash||'').match(/#ep(\d+)/); var chosen;
    if(m){ var n=parseInt(m[1],10); chosen=eps.filter(function(e){return e.num===n;})[0]; }
    if(!chosen) chosen=eps[0];
    if(!chosen) return [];
    return chosen.urls.map(function(u){ var h=''; try{ h=new URL(u).hostname.replace(/^www\./,''); }catch(e){}
      return { name:(h||'Lecteur')+(ver?' ('+ver+')':''), url:u }; });
  }
  window.__P={ getHome:getHome, search:search, getGenre:getGenre, getMovies:getMovies, getTvShows:getTvShows,
    getMovie:getMovie, getTvShow:getTvShow, getEpisodesBySeason:getEpisodesBySeason, extractServers:extractServers };
})();
