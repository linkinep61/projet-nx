/* frenchanime.js — French Anime (french-anime.com) en WebJS. CF via WebView.
 * Serveurs = embeds host dans div.eps "num!url1,url2,...". getVideo = Extractor.extract.
 * PAS de TMDB -> posters du site. IMPORTANT : french-anime CF THROTTLE l'IP si trop de
 * fetches -> getHome ne fait QU'UN fetch (carrousel + 3 rails du site). Onglets FR/VOSTFR
 * ont leurs propres fetches (2 chacun). Ne PAS ajouter de rails multi-fetch au home.
 */
(function () {
  var BASE = location.origin;
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
  function detailBasics(doc){ doc=doc||document;
    var h1=doc.querySelector('h1[itemprop=name]');
    var pimg=doc.querySelector('div.mov-img img[itemprop=thumbnailUrl]')||doc.querySelector('.mov-img img');
    var d=doc.querySelector('[itemprop=description]')||doc.querySelector('div.mov-desc');
    return { title:h1?h1.textContent.trim():'', poster:abs(imgUrl(pimg)), overview:d?d.textContent.trim():'' };
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
  async function getHome(){
    // UN SEUL fetch (carrousel + 3 rails du site) — french-anime throttle si +de fetches.
    var doc=document;
    try{ var d=await fetchDoc(''); if(d.querySelectorAll('.block-main').length||d.querySelectorAll('div.mov').length||d.querySelectorAll('.owl-carousel .item').length) doc=d; }catch(e){}
    var cats=[];
    var feat=parseOwl(doc); if(feat.length) cats.push({ name:'', items:feat });
    [].slice.call(doc.querySelectorAll('.block-main')).forEach(function(b){
      var t=b.querySelector('.block-title,h2,.bmt'); var items=parseList(b);
      if(items.length) cats.push({ name:clean(t?t.textContent:'')||'Animes', items:items });
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
  async function getMovies(page){ page=page||1; var out=[];
    try{ var d1=await fetchDoc('films-vf-vostfr/page/'+page);
      [].slice.call(d1.querySelectorAll('div.mov')).forEach(function(m){ if(movVF(m)){ var it=parseMov(m); if(it){ it.type='movie'; it.isSeries=false; out.push(it); } } });
    }catch(e){}
    try{ var d2=await fetchDoc('animes-vf/page/'+page+'/');
      [].slice.call(d2.querySelectorAll('div.mov')).forEach(function(m){ var it=parseMov(m); if(it){ it.type='movie'; it.isSeries=true; out.push(it); } });
    }catch(e){}
    return out;
  }
  async function getTvShows(page){ page=page||1; var out=[];
    try{ var d1=await fetchDoc('animes-vostfr/page/'+page+'/');
      [].slice.call(d1.querySelectorAll('div.mov')).forEach(function(m){ var it=parseMov(m); if(it){ it.type='tv'; it.isMovie=false; out.push(it); } });
    }catch(e){}
    try{ var d2=await fetchDoc('films-vf-vostfr/page/'+page);
      [].slice.call(d2.querySelectorAll('div.mov')).forEach(function(m){ if(!movVF(m)){ var it=parseMov(m); if(it){ it.type='tv'; it.isMovie=true; out.push(it); } } });
    }catch(e){}
    return out;
  }
  async function getMovie(id){ var b=detailBasics(document); return { id:id, type:'movie', title:b.title, poster:b.poster, overview:b.overview }; }
  async function getTvShow(id,lang){
    var b=detailBasics(document);
    var sNum=(b.title.match(/[Ss]aison\s*(\d+)/)||[])[1];
    var verLi=[].slice.call(document.querySelectorAll('ul.mov-list li')).filter(function(li){var l=li.querySelector('.mov-label');return l&&/version/i.test(l.textContent);})[0];
    var ver=verLi?verLi.textContent.replace(/version/i,'').replace(/[:]/g,'').replace(/\s+/g,' ').trim():'';
    return { id:id, type:'tv', title:b.title, poster:b.poster, overview:b.overview,
      seasons:[ { id:id+'#s', number:parseInt(sNum||'1',10)||1, title:ver||('Saison '+(sNum||1)) } ] };
  }
  async function getEpisodesBySeason(seasonId,lang){
    var showPath=seasonId.split('#')[0];
    return parseEps(document).map(function(e){ return { id:showPath+'#ep'+e.num, number:e.num }; });
  }
  async function extractServers(lang){
    var eps=parseEps(document); if(!eps.length) return [];
    var m=(location.hash||'').match(/#ep(\d+)/); var chosen;
    if(m){ var n=parseInt(m[1],10); chosen=eps.filter(function(e){return e.num===n;})[0]; }
    if(!chosen) chosen=eps[0];
    if(!chosen) return [];
    return chosen.urls.map(function(u){ var h=''; try{ h=new URL(u).hostname.replace(/^www\./,''); }catch(e){} return { name:h||'Lecteur', url:u }; });
  }
  window.__P={ getHome:getHome, search:search, getGenre:getGenre, getMovies:getMovies, getTvShows:getTvShows,
    getMovie:getMovie, getTvShow:getTvShow, getEpisodesBySeason:getEpisodesBySeason, extractServers:extractServers };
})();