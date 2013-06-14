package finallab;

import java.io.*;
import java.util.*;
import java.awt.*;
import java.awt.image.*;
import java.awt.event.*;
import javax.swing.*;

import april.jcam.*;
import april.util.*;
import april.jmat.*;
import april.vis.*;
import april.image.*;
import april.jmat.geom.*;

import finallab.lcmtypes.*;

import lcm.lcm.*;

public class Pimu implements LCMSubscriber 
{

	//GUI Variables
	static VisWorld vw;
	static VisLayer vl;
	static VisCanvas vc;
	static JFrame jf;  
	  
	//Gyro and Accelerometer values
	public double[] RPYdot; 	
	public double[] XYZdot;
	public double yaw;
	
	public int[] prev_integrator;	
	public int[] integrator;
	public int[] prev_accel;	
	public int[] accel;	
	
	public double[] sum_angvel;
	public double[] sum_accel;

	public double[] RPYdiff; 	
	public double[] XYZdiff;
		
	public double prev_time,time;
	public int num_calibs;

	static final int NUMCALIBRATIONS = 100;

	LCM lcm;
	pimu_t gyros;
	boolean calibrating;
	boolean calibdone;
	boolean displayGUI;
	
	Pimu(boolean DisplayGUI)
	{
		RPYdot = new double[8]; 
		XYZdot = new double[3];
		prev_integrator = new int[8];
		integrator = new int[8];
		prev_accel = new int[3];
		accel = new int[3];
		sum_angvel = new double[8];
		sum_accel = new double[8];
		RPYdiff = new double[8];
		XYZdiff = new double[3];	
	
		if (DisplayGUI)
		{	
			//Jframe and VisLayer initialization
			vw = new VisWorld();
			vl  = new VisLayer(vw);
			vc = new VisCanvas(vl);
			jf = new JFrame("Gyro Visualization"); 		
			jf.setLayout(new BorderLayout());
			jf.add(vc, BorderLayout.CENTER);	
			VzGrid.addGrid(vw);
			vl.cameraManager.uiLookAt(
					new double[] {-1, -1, 1 },
					new double[] { 0,  0, 0.00000 },
					new double[] { 0.13802,  0.40084, 0.90569 }, true);

			jf.setSize(800, 800);
			jf.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
			jf.setVisible(true);
			//vl.addEventHandler(this); 
			displayGUI = true;
		}  

		//Initialize previous variables to zero
		num_calibs = 0;
		prev_time=0;
		calibrating=false;
		calibdone=false;
		for (int i=0; i<8; i++)
		{
			if (i<3)
			{	
				prev_accel[i] = 0;
				sum_accel[i] = 0;
				XYZdot[i] = 0;
			}
			prev_integrator[i] = 0;
			sum_angvel[i] = 0;
			RPYdot[i] = 0;
		}

		//Create singleton and subscribe to LCM	
		lcm = LCM.getSingleton();
		lcm.subscribe("6_PIMU", this);
		
	}
	
	public void calibrate()
	{
		calibrating=true;
	}

	public void recalibrate()
	{
		num_calibs = 0;
		prev_time=0;
		calibrating=true;
		calibdone=false;
		for (int i=0; i<8; i++)
		{
			if (i<3)
			{	
				prev_accel[i] = 0;
				sum_accel[i] = 0;
				XYZdot[i] = 0;
			}
			prev_integrator[i] = 0;
			sum_angvel[i] = 0;
			RPYdot[i] = 0;
		}
	}
	
	public void messageReceived(LCM lcm, String channel, LCMDataInputStream dins)
	{
	
		try
		{
			if(channel.equals("6_PIMU"))
			{
				gyros = new pimu_t(dins);
				
				if (calibrating  && !calibdone)
				{
					getSensorData();
					System.out.printf("numcalibs:%d\n",num_calibs-1);
					num_calibs++;
					if (num_calibs == NUMCALIBRATIONS+2)  
					{
						calibdone = true;
						calibrating = false;
						System.out.printf("CALIBRATED!\n");
						//create the diff values used in calibration
						for (int i=0; i<8; i++)
						{
							if (i<3) //divide by num_calibs-1 since first point is skipped
								XYZdiff[i] = sum_accel[i]/(num_calibs-2);
							RPYdiff[i] = sum_angvel[i]/(num_calibs-2);
						}
						
						
						yaw=0;
						calculateDots();	
					}
					else if (num_calibs>1) //the first diff must be skipped	
						addToSums();
				}
				else if (calibdone && !calibrating)
				{
					getSensorData();
					calculateDots();
				}
				
				if (displayGUI) //display graphs of data for debugging
					updateGUI();
					

				updatePrevs();				

			}
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}	
	}
	
	public void getSensorData()
	{
		for (int i=0; i<8; i++)
		{
			if (i<3)
				accel[i] = gyros.accel[i];
			integrator[i] = gyros.integrator[i];
		}	
		time = gyros.utime_pimu;
	}
		
	public void addToSums()
	{	
		double timediff = time-prev_time;
		for (int i=0; i<8; i++)
		{
			double diff = (integrator[i] - prev_integrator[i])/timediff;
			//System.out.printf("integrator diff:%f\n",diff);
			sum_angvel[i] += diff;
		}
		//System.out.printf("sumangvel:%f\n",sum_angvel[0]);
		for (int i=0; i<3; i++)
		{
			double diff = accel[i]-prev_accel[i];
			sum_accel[i] += diff;
		}
	}
	
	public void calculateDots()
	{
		if (!calibdone)
		{
			System.out.printf("NOT CALIBRATED\n");
			return;
		}
		
		
	 	double timediff = time-prev_time;
	 	
	 	double[] angvel_dat = new double[8]; 			
		for (int i=0; i<8; i++)
		{
			angvel_dat[i] = ((integrator[i]-prev_integrator[i])/timediff) - RPYdiff[i];	
			//angvel_dat[i] = ((integrator[i]-prev_integrator[i])/timediff);	
		}
			
		//average out the multiple channels for each angular direction
		RPYdot[0] = (angvel_dat[6]+angvel_dat[7])/2;
		RPYdot[1] = (angvel_dat[2]+angvel_dat[3])/2;
		RPYdot[2] = (angvel_dat[0]+angvel_dat[1]+angvel_dat[4]+angvel_dat[5])/4;		
		
		yaw += ((RPYdot[2]*timediff)/1000000)*(Math.PI/180);
		
		for (int i=0; i<3; i++)
		{
			XYZdot[i] = (accel[i]-prev_accel[i]-XYZdiff[i])*(timediff/10000000); //must integrate, units???
			//System.out.printf("accel:%d, prev_accel:%d, XYZdiff:%d, timediff:%f, XYZ%d:%f\n",
				//accel[i],prev_accel[i],XYZdiff[i],timediff,i,XYZdot[i]);
		}
		//System.out.println();

	}
	
	public void updatePrevs()
	{
		prev_time = time;
		for (int i=0; i<8; i++)
		{
			if (i<3)
				prev_accel[i] = accel[i];
						
			prev_integrator[i] = integrator[i];	
		}
	}	

	synchronized void updateGUI()
	{  
		
		//Intialize an Array of colors for bars
		ArrayList<VzMesh.Style> meshColors = new ArrayList<VzMesh.Style>();
		
		meshColors.add(0,new VzMesh.Style(Color.red));
		meshColors.add(1,new VzMesh.Style(Color.green));
		meshColors.add(2,new VzMesh.Style(Color.blue));
	 	
		//Create Bars	
		ArrayList<VisObject> gyrobars = new ArrayList<VisObject>();
		for (int i=0; i<3; i++)
		{
			gyrobars.add(i,new VisChain(LinAlg.translate((0.20*i),0.0,0.001*RPYdot[i]),
				new VzBox(0.20,0.05,Math.abs(RPYdot[i]*0.002), meshColors.get(i))));		
		}
		ArrayList<VisObject> accelbars = new ArrayList<VisObject>();
		for (int i=0; i<3; i++)
		{
			accelbars.add(i,new VisChain(LinAlg.translate((0.20*i),0.2,0.001*XYZdot[i]),
				new VzBox(0.20,0.05,Math.abs(XYZdot[i]*0.002), meshColors.get(i))));		
		}		
		//Create Buffer and swap
		VisWorld.Buffer vb = vw.getBuffer("chart");		
		for (int i=0; i<3; i++)
		{
			vb.addBack(gyrobars.get(i));
			vb.addBack(accelbars.get(i));
		}
		vb.addBack(new VzText("BLAH"));
		//vb.addBack(new VzAxes());
		vb.swap();	
	}
	  	
	public static void main(String[] args) throws Exception
	{
		Pimu pg = new Pimu(false);
		pg.calibrate();
	
		while(true)
		{
			Thread.sleep(1000);
		}
	}
	
	public double[] getRPYdot() 
	{
		if (!calibdone)
		{	
			return(new double[]{0,0,0});
		}
		return (new double[]{RPYdot[0], RPYdot[1], RPYdot[2]});
	}
	public double[] getXYZdot() 
	{
		if (!calibdone)
		{
			return(new double[]{0,0,0});
		}
		return (new double[]{XYZdot[0], XYZdot[1], XYZdot[2]});
	}
	//public double getDeltaYaw(){return deltayaw;} 
	public double getTimeDiff(){return (time-prev_time);}	
}	
		
		
		
