cp index.html index.html.original
sed '
  s/url.*petstore.*/url = window.location.href.replace(\/swagger-ui.*\/, "render-ws\/swagger.json");/
  s/apisSorter: "alpha",/apisSorter: "alpha", validatorUrl: null, operationsSorter: function(a, b) { var methodMap = { 'get': 1, 'put': 2, 'post': 3, 'delete': 4 }; if (a.method in methodMap \&\& b.method in methodMap) { var aMethodValue = methodMap[a.method]; var bMethodValue = methodMap[b.method]; if (aMethodValue == bMethodValue) { return a.path.localeCompare(b.path); } else { return aMethodValue - bMethodValue; } } else { return -1; } },/
' index.html.original > index.html

# workaround bug in current swagger.json that breaks swagger-ui.js
cp swagger-ui.js swagger-ui.js.original
sed '
  s/var ref = property.\$ref;/if (typeof property === "undefined") { property = ""; console.log("skipping undefined property"); } var ref = property.\$ref;/
' swagger-ui.js.original > swagger-ui.js