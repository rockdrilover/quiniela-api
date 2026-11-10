-- Logos de los equipos: archivos estáticos que sirve el front en /equipos/<ABREVIATURA>.png
UPDATE equipos SET url_logo = '/equipos/' || abreviatura || '.png'
WHERE abreviatura IN ('AME', 'ATE', 'ATS', 'ASL', 'CAZ', 'JUA', 'CHV', 'LEO', 'MTY',
                      'NEC', 'PAC', 'PUE', 'PUM', 'QRO', 'SAN', 'TIG', 'TIJ', 'TOL');
