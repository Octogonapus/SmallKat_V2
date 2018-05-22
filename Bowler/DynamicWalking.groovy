import java.time.Duration;
import java.util.ArrayList;

import javafx.application.Platform;

import org.reactfx.util.FxTimer;

import com.neuronrobotics.sdk.addons.kinematics.DHParameterKinematics;
import com.neuronrobotics.sdk.addons.kinematics.MobileBase;
import com.neuronrobotics.sdk.addons.kinematics.math.RotationNR;
import com.neuronrobotics.sdk.addons.kinematics.math.TransformNR;
import com.neuronrobotics.sdk.util.ThreadUtil;
import com.neuronrobotics.sdk.addons.kinematics.IDriveEngine;
import com.neuronrobotics.sdk.common.Log;
import Jama.Matrix;

enum WalkingState {
    Rising,ToHome,ToNewTarget,Falling
}

if(args==null){
	double stepOverHeight=15;
	long stepOverTime=40;
	Double zLock=-12;
	Closure calcHome = { DHParameterKinematics leg -> 
			TransformNR h=leg.calcHome() 
	 		TransformNR  legRoot= leg.getRobotToFiducialTransform()
			TransformNR tr = leg.forwardOffset(new TransformNR())
			tr.setZ(zLock)
			//Bambi-on-ice the legs a bit
			if(legRoot.getY()>0){
				//tr.translateY(5)
			}else{
				//tr.translateY(-5)
			}
			
			return tr;
	
	}
	boolean usePhysicsToMove = true;
	long stepCycleTime =200
	
	args =  [stepOverHeight,stepOverTime,zLock,calcHome,usePhysicsToMove,stepCycleTime]
}

return new com.neuronrobotics.sdk.addons.kinematics.IDriveEngine (){
	boolean resetting=false;
	double stepOverHeight=(double)args.get(0);
	long stepOverTime=(long)args.get(1);
	private Double zLock=(Double)args.get(2);
	Closure calcHome =(Closure)args.get(3);
	boolean usePhysics=(args.size()>4?((boolean)args.get(4)):false);
	long stepCycleTime=args.get(5)
	long timeOfCycleStart= System.currentTimeMillis();
	int stepCycyleActiveIndex =0
	int numStepCycleGroups = 2

	ArrayList<DHParameterKinematics> legs;
	HashMap<Integer,ArrayList<DHParameterKinematics> > cycleGroups=new HashMap<>();
	HashMap<DHParameterKinematics,double[] > cycleStartPoint=new HashMap<>();
	TransformNR previousGLobalState;
	TransformNR target;
	RotationNR rot;
	int resettingindex=0;
	private long reset = System.currentTimeMillis();
	
	Thread stepResetter=null;
	boolean threadDone=false
	WalkingState walkingState= WalkingState.Rising
	MobileBase source 
	TransformNR newPose
	long miliseconds
	boolean timout = true
	long loopTimingMS =5
	long timeOfLastLoop = System.currentTimeMillis()
	int numlegs
	double gaitIntermediatePercentage 
	TransformNR global
	public void resetStepTimer(){
		reset = System.currentTimeMillis();
	}
	def getUpLegs(){
		return cycleGroups.get(stepCycyleActiveIndex)
	}
	def getDownLegs(){
		return cycleGroups.get(stepCycyleActiveIndex).collect{
				if(!upLegs.contains(it))
					return it
			}
	}
	public void walkingCycle(){
		long incrementTime = (System.currentTimeMillis()-reset)
		if(incrementTime>miliseconds){
			timout = true
		}else
			timout = false
		long timeSince=	(System.currentTimeMillis()-timeOfCycleStart)
		if(timeSince>stepCycleTime){
			//print "\r\nWalk cycle loop time "+(System.currentTimeMillis()-timeOfCycleStart) +" "
			getUpLegs().collect{
			 	cycleStartPoint.put(it,leg.getCurrentJointSpaceVector())
			}
			timeOfCycleStart=System.currentTimeMillis()
			stepCycyleActiveIndex++
			if(stepCycyleActiveIndex==numStepCycleGroups){
				stepCycyleActiveIndex=0;
			}
			
		}else{
			
			//println " Waiting till "+(timeOfCycleStart+stepCycleTime)+" is "+System.currentTimeMillis()leg
		}
		
		double gaitTimeRemaining = (double) (System.currentTimeMillis()-timeOfCycleStart)
		double gaitPercentage = gaitTimeRemaining/(double)(stepCycleTime)
		//println "Cycle = "+stepCycyleActiveIndex+" "+gaitPercentage
		if(gaitPercentage<0.25){
			walkingState= WalkingState.Rising
			gaitIntermediatePercentage=gaitPercentage*4.0
		}else if(gaitPercentage<0.5) {
			walkingState= WalkingState.ToHome
			gaitIntermediatePercentage=(gaitPercentage-0.25)*4.0
		}else if(gaitPercentage<0.75) {
			walkingState= WalkingState.ToNewTarget
			gaitIntermediatePercentage=(gaitPercentage-0.5)*4.0
		}else {
			walkingState= WalkingState.Falling
			gaitIntermediatePercentage=(gaitPercentage-0.75)*4.0
		}
		def upLegs = getUpLegs()
		def downLegs =getDownLegs()
		//println upLegs
		//println downLegs
		for (def leg :upLegs){
			if(leg!=null)
				upStateMachine(leg,gaitIntermediatePercentage)
			
		}
		
		if(!timout){
			double timeRemaining =(double) (System.currentTimeMillis()-reset)
			double percentage =(double)(loopTimingMS)/ (double)(miliseconds)
			for (def leg :downLegs){
				if(leg!=null)
					downMove( leg, percentage)
			}
		}
		
		
	}
	private void downMove(def leg,double percentage){
		//println "Down Moving to "+percentage
		def pose =compute(leg,percentage,newPose)
		leg.setDesiredTaskSpaceTransform(pose, 0);
	}
	private TransformNR compute(def leg,double percentage,def bodyMotion){
		TransformNR footStarting = leg.getCurrentTaskSpaceTransform();
		double[] joints = cycleStartPoint.get(leg)	
		TransformNR armOffset = leg.forwardKinematics(joints)	
		global=global.times(bodyMotion);// new global pose
		Matrix btt =  leg.getRobotToFiducialTransform().getMatrixTransform();
		Matrix ftb = global.getMatrixTransform();// our new target
		Matrix current = armOffset.getMatrixTransform();
		Matrix mForward = ftb.times(btt).times(current);
		TransformNR inc =new TransformNR( mForward);
		inc.setZ(zLock);
		double xinc=(footStarting.getX()-inc.getX())*percentage;
		double yinc=(footStarting.getY()-inc.getY())*percentage;
		//apply the increment to the feet
		println "Feet increments x = "+xinc+" y = "+yinc
		footStarting.translateX(xinc);
		footStarting.translateY(yinc);
		footStarting.setZ(zLock);
		return footStarting
	}
	private void upStateMachine(def leg,double percentage){
		//println "Up Moving to "+percentage
		
		def tf
		if(timout)
			tf = leg.getCurrentTaskSpaceTransform()
		else
			tf = leg.getCurrentTaskSpaceTransform()
			//tf = compute(leg,1,newPose.inverse())
		switch(walkingState){
		case WalkingState.Rising:
			tf.setZ(zLock+(stepOverHeight*percentage));
			break;
		case WalkingState.ToHome:
			tf = dynamicHome( leg)
			tf.setZ(zLock+(stepOverHeight));
			break;
		case WalkingState.ToNewTarget:
			tf.setZ(zLock+(stepOverHeight));
			break;
		case WalkingState.Falling:
			tf.setZ(zLock+stepOverHeight-(stepOverHeight*percentage));
			break;
		}
		leg.setDesiredTaskSpaceTransform(tf, 0);
	}
	@Override
	public void DriveArc(MobileBase source, TransformNR newPose, double seconds) {
		DriveArcLocal( source,  newPose,  seconds,true);
	}
	/**
	 * Calc Inverse kinematics of a limb .
	 *
	 * @param jointSpaceVect the joint space vect
	 * @return the transform nr
	 */
	public double[] calcForward(DHParameterKinematics leg ,TransformNR transformTarget){
		return leg.inverseKinematics(leg.inverseOffset(transformTarget));
	}
	boolean check(DHParameterKinematics leg,TransformNR newPose){
		TransformNR stepup = newPose.copy();
		stepup.setZ(stepOverHeight + zLock );
		if(!leg.checkTaskSpaceTransform(newPose)){
			return false
		}
		if(!leg.checkTaskSpaceTransform(stepup)){
			return false
		}
		return true
	}
	
	private void walkLoop(){
		long time = System.currentTimeMillis()-timeOfLastLoop
		if(time>loopTimingMS){
			//print "\r\nWalk cycle loop time "+(System.currentTimeMillis()-timeOfLastLoop) +" "
			timeOfLastLoop=System.currentTimeMillis()
			walkingCycle()
			//print " Walk cycle took "+(System.currentTimeMillis()-timeOfLastLoop) 
		}
		if(reset+3000 < System.currentTimeMillis()){
			println "FIRING reset from reset thread"
			resetting=true;
			long tmp= reset;
			
			for(int i=0;i<numlegs;i++){
				StepHome(legs.get(i))
			}
			resettingindex=numlegs;
			resetting=false;
			threadDone=true;
			stepResetter=null;
			
		}
	}
	def dynamicHome(def leg){
		//TODO apply dynamics to home location
		return calcHome(leg)
	}
	private void StepHome(def leg){
		try{
			def home = dynamicHome(leg)
			TransformNR up = home.copy()
			up.setZ(stepOverHeight + zLock )
			TransformNR down = home.copy()
			down.setZ( zLock )
			try {
				// lift leg above home
				//println "lift leg  "+up
				leg.setDesiredTaskSpaceTransform(up, 0);
			} catch (Exception e) {
				//println "Failed to reach "+up
				BowlerStudio.printStackTrace(e);
			}
			ThreadUtil.wait((int)stepOverTime);
			try {
				//step to new target
				//println "step leg down "+down
				
				leg.setDesiredTaskSpaceTransform(down, 0);
				//set new target for the coordinated motion step at the end
			} catch (Exception e) {
				//println "Failed to reach "+down
				BowlerStudio.printStackTrace(e);
			}
			ThreadUtil.wait((int)stepOverTime);
		}catch(Exception e){
			BowlerStudio.printStackTrace(e)
		}
	}
	public void DriveArcLocal(MobileBase s, TransformNR n, double sec, boolean retry) {
		try{
			//println "Walk update "+n
			source=s;
			newPose=n.copy()
			//newPose=new TransformNR()
			miliseconds = Math.round(sec*1000)
			numlegs = source.getLegs().size();
			legs = source.getLegs();
			global= source.getFiducialToGlobalTransform();
			if(global==null){
				global=new TransformNR()
				source.setGlobalToFiducialTransform(global)
			}
			if(stepResetter==null){
				try{
					timeOfCycleStart= System.currentTimeMillis();
					for(int i=0;i<numStepCycleGroups;i++){
						if(cycleGroups.get(i)==null){
							def cycleSet = []
							for(def leg:source.getLegs()){
								TransformNR  legRoot= leg.getRobotToFiducialTransform()
								if(legRoot.getX()>0&&legRoot.getY()>0 && i==0){
									cycleSet.add(leg)
								}else
								if(legRoot.getX()<0&&legRoot.getY()<0 && i==0){
									cycleSet.add(leg)
								}else
								if(legRoot.getX()>0&&legRoot.getY()<0 && i==1){
									cycleSet.add(leg)
								}else
								if(legRoot.getX()<0&&legRoot.getY()>0 && i==1){
									cycleSet.add(leg)
								}
							}
							//println "Adding "+cycleSet.size()+" to index "+i
							cycleGroups.put(i, cycleSet)
						}
					}
				}catch(Exception e){
					BowlerStudio.printStackTrace(e)
				}
				stepResetter = new Thread(){
					public void run(){
						try{
							threadDone=false;
							walkingState= WalkingState.Rising
							stepCycyleActiveIndex=0;
							println "Starting step reset thread"
							while(source.isAvailable() && threadDone==false){
								Thread.sleep(0,10)// avoid thread lock
								try{	
									walkLoop();
								}catch(Exception e){
									BowlerStudio.printStackTrace(e)
								}
							}
							println "Finished step reset thread"
						}catch(Exception e){
							BowlerStudio.printStackTrace(e)
						}
					}
					
					
				};
				stepResetter.start();
			}
			resetStepTimer();
		}catch(Exception e){
			BowlerStudio.printStackTrace(e)
		}
	}

	@Override
	public void DriveVelocityStraight(MobileBase source, double cmPerSecond) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void DriveVelocityArc(MobileBase source, double degreesPerSecond,
			double cmRadius) {
		// TODO Auto-generated method stub
		
	}



}

	
		