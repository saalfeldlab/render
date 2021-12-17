import urllib.request
import sys
import json
import os
import smtplib
from pathlib import Path
response = urllib.request.urlopen('http://'+sys.argv[9]+'/render-ws/v1/owner/'+sys.argv[1]+'/project/'+sys.argv[2]+'/stack/'+sys.argv[3]+'/bounds')
data_json=json.loads(response.read())
width=int(data_json['maxX']-data_json['minX'])
height=int(data_json['maxY']-data_json['minY'])
X=data_json['minX']
Y=data_json['minY']
minZ=data_json['minZ']
maxZ=data_json['maxZ']
#os.system("pip3 install pymongo")
#os.system("pip3 install dnspython")
for z in range(int(minZ),int(maxZ+1)):
        source_folder=sys.argv[8]+sys.argv[1]+"/"+sys.argv[2]+"/"+sys.argv[3]
        Path(source_folder).mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve("http://"+sys.argv[9]+"/render-ws/v1/owner/"+sys.argv[1]+"/project/"+sys.argv[2]+"/stack/"+sys.argv[3]+"/z/"+str(z)+"/box/"+str(X)+","+str(Y)+","+str(width)+","+str(height)+",1/jpeg-image?scale=1", source_folder+"/"+sys.argv[1]+"_"+sys.argv[2]+"_"+sys.argv[3]+"_"+str(z)+".jpg")
        destination_folder=sys.argv[6]+sys.argv[1]+"/"+sys.argv[2]+"/"+sys.argv[3]+"/"+sys.argv[1]+"_"+sys.argv[2]+"_"+sys.argv[3]+"_"+str(z)+".jpg"
        Path(destination_folder).mkdir(parents=True, exist_ok=True)
        os.system(sys.argv[5]+" "+source_folder+"/"+sys.argv[1]+"_"+sys.argv[2]+"_"+sys.argv[3]+"_"+str(z)+".jpg"+" "+destination_folder)

