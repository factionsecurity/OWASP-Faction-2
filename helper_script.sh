echo -e "\n### Bringing up network bridge ###"
sudo /home/cuckoo/vmcloak/bin/vmcloak-qemubridge br0 192.168.30.1/24
echo -e "\n### Mounting ISO ###"
sudo mount -o loop,ro /home/cuckoo/win10x64.iso /mnt/win10x64
