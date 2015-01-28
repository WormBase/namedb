function xhr(url, callback) {
    var req = new XMLHttpRequest();
    req.onreadystatechange = function() {
    	if (req.readyState == 4) {
    	    if (req.status >= 300) {
    		callback(null, 'Error code ' + req.status);
    	    } else {
    		callback(JSON.parse(req.responseText));
    	    }
    	}
    };
    
    req.open('GET', url, true);
    req.responseType = 'text';
    req.send('');
}

function initAutocomplete(i) {
    i.addEventListener('keydown', function(ev) {
        xhr('/api/lookup?prefix=' + i.value, function(resp) {
            if (resp && resp.indexOf(i.value) >= 0) {
                i.style.background = '#aaeeaa';
            } else {
                i.style.background = 'white';
            }
        });
    }, false);
}

document.addEventListener('DOMContentLoaded', function(ev) {
    var acs = document.querySelectorAll('input.autocomplete');
    for (i = 0; i < acs.length; ++i) {
        initAutocomplete(acs[i]);
    }
}, false);
