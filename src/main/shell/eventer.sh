java -cp $HOME/.m2/repository/io/netty/netty-all/4.0.13.Final/netty-all-4.0.13.Final.jar:$HOME/glassfish4/glassfish/modules/javax.json.jar:eventer.jar ru.carabi.server.eventer.Main http://127.0.0.1:2008/carabiserver_dev/ 9234 2>eventer.log &
disown
